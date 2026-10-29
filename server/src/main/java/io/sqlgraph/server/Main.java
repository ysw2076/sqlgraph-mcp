package io.sqlgraph.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import java.util.function.Consumer;

@Command(name = "sqlgraph", mixinStandardHelpOptions = true, version = Main.VERSION,
        description = "DB 스키마와 소스 속 SQL 을 인덱싱하는 MCP 서버",
        subcommands = {Main.Serve.class, Main.Index.class, Main.Call.class, Main.Init.class,
                Installer.Install.class, Installer.Uninstall.class})
public final class Main implements Runnable {

    public static final String VERSION = "0.3.1";

    public static void main(String[] args) {
        System.exit(new CommandLine(new Main()).execute(args));
    }

    @Override
    public void run() {
        new CommandLine(this).usage(System.err);
    }

    static Consumer<String> logger(boolean quiet) {
        return quiet ? s -> { } : s -> System.err.println("[sqlgraph] " + s);
    }

    /** 공통: 작업 공간 만들기. -c 로 설정 파일을 주면 그 프로젝트에 고정 */
    static Workspace workspace(Path config, Consumer<String> log) throws Exception {
        Workspace ws = new Workspace(SqlGraphHome.fromEnv(), Path.of(""), log);
        Path cfg = config;
        if (cfg == null) {
            String env = System.getenv("SQLGRAPH_CONFIG");
            if (env != null && !env.isBlank()) cfg = Path.of(env);
        }
        if (cfg != null) {
            Config c = Config.load(cfg, SqlGraphHome.fromEnv().indexFor(cfg.toAbsolutePath().getParent()));
            ws.pin(c);
        }
        return ws;
    }

    @Command(name = "serve", description = "MCP 서버 실행 (stdio). Claude Code 등이 이 명령으로 띄웁니다")
    static class Serve implements Callable<Integer> {
        @Option(names = {"-c", "--config"}, description = "특정 sqlgraph.yml 에 고정 (보통 필요 없음)")
        Path config;

        @Option(names = {"-q", "--quiet"}, description = "로그 숨김")
        boolean quiet;

        @Override
        public Integer call() throws Exception {
            // stdout 은 MCP 프로토콜 전용. 실수로 찍히는 출력은 stderr 로 보낸다
            PrintStream protocolOut = new PrintStream(new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8);
            PrintStream err = new PrintStream(new FileOutputStream(FileDescriptor.err), true, StandardCharsets.UTF_8);
            System.setOut(err);
            System.setErr(err);
            Consumer<String> log = logger(quiet);
            ObjectMapper om = new ObjectMapper();
            try (Workspace ws = workspace(config, log)) {
                log.accept("sqlgraph " + VERSION + " 시작 (폴더: " + ws.cwd() + ", 데이터: " + SqlGraphHome.fromEnv().dir() + ")");
                new McpServer(om, new Tools(ws, om), VERSION, log).run(System.in, protocolOut);
            }
            return 0;
        }
    }

    @Command(name = "index", description = "프로젝트 인덱싱. 예: sqlgraph index C:\\work\\my-project")
    static class Index implements Callable<Integer> {
        @Parameters(index = "0", arity = "0..1", description = "프로젝트 폴더 (기본: 현재 폴더)")
        Path path;

        @Option(names = "--profile", description = "Spring 프로필 (기본: local → dev → 기본 설정)")
        String profile;

        @Option(names = "--skip-db", description = "DB 접속 없이 소스만")
        boolean skipDb;

        @Option(names = {"-c", "--config"}, description = "sqlgraph.yml 지정")
        Path config;

        @Option(names = {"-q", "--quiet"}, description = "진행 로그 숨김")
        boolean quiet;

        @Override
        public Integer call() throws Exception {
            try (Workspace ws = workspace(config, logger(quiet))) {
                String target = path != null ? path.toAbsolutePath().toString()
                        : config != null ? ws.current().toString() : ws.cwd().toString();
                System.out.println(new Tools(ws, new ObjectMapper()).indexProject(target, profile, skipDb));
            }
            return 0;
        }
    }

    @Command(name = "call", description = "도구 직접 호출 (테스트용). 예: sqlgraph call describe_table '{\"table\":\"HR_EMP\"}'")
    static class Call implements Callable<Integer> {
        @Parameters(index = "0", description = "도구 이름")
        String tool;

        @Parameters(index = "1", arity = "0..1", description = "JSON 인자")
        String json;

        @Option(names = {"-c", "--config"}, description = "sqlgraph.yml 지정")
        Path config;

        @Option(names = {"-q", "--quiet"}, description = "진행 로그 숨김")
        boolean quiet;

        @Override
        public Integer call() throws Exception {
            ObjectMapper om = new ObjectMapper();
            try (Workspace ws = workspace(config, logger(quiet))) {
                JsonNode args = json == null ? om.createObjectNode() : om.readTree(json);
                if (args instanceof ObjectNode o && !o.has("project") && !tool.equals("index_project")
                        && !tool.equals("list_projects")) {
                    // CLI 에서는 현재 폴더를 기본 프로젝트로
                    try {
                        ws.resolve(null);
                    } catch (IllegalArgumentException e) {
                        o.put("project", ws.cwd().toString());
                    }
                }
                System.out.println(new Tools(ws, om).call(tool, args));
            } catch (IllegalArgumentException e) {
                System.err.println(e.getMessage());
                return 1;
            }
            return 0;
        }
    }

    @Command(name = "init", description = "현재 폴더에 sqlgraph.yml 예시 생성 (자동 탐지가 안 될 때만 필요)")
    static class Init implements Callable<Integer> {
        @Override
        public Integer call() throws Exception {
            Path f = Path.of("sqlgraph.yml");
            if (Files.exists(f)) {
                System.err.println("sqlgraph.yml 이 이미 있습니다");
                return 1;
            }
            Files.writeString(f, TEMPLATE, StandardCharsets.UTF_8);
            System.out.println("sqlgraph.yml 을 만들었습니다. DB 접속 정보를 채운 뒤 'sqlgraph index' 로 확인하세요.");
            return 0;
        }

        static final String TEMPLATE = """
                # sqlgraph-mcp 설정 — 자동 탐지(application.yml, .env)가 안 될 때만 필요합니다
                # 상대 경로는 이 파일 위치 기준
                project: .

                databases:
                  - id: main
                    type: jdbc
                    url: jdbc:mariadb://localhost:3306/mydb
                    user: reader
                    passwordEnv: SQLGRAPH_DB_PW    # 비밀번호는 환경변수로 (password: 로 직접 써도 됨)
                    # schema: HR                   # Oracle/PostgreSQL 등 스키마 지정
                    # driverJar: ./drivers/tibero7-jdbc.jar   # 자동 다운로드가 안 되는 드라이버
                    # driverClass: com.tmax.tibero.jdbc.TbDriver

                  # DB 에 접속할 수 없으면 DDL 파일로:
                  # - id: legacy
                  #   type: ddl
                  #   files: ["ddl/*.sql"]
                  #   dialect: oracle

                # autodetect: true   # databases 와 함께 자동 탐지도 하려면
                sources:
                  include: ["**/*.xml", "**/*.sql"]
                  exclude: ["**/test/**"]
                """;
    }
}
