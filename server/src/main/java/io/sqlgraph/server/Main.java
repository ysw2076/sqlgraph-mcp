package io.sqlgraph.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.sqlgraph.core.index.Indexer;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.FileOutputStream;
import java.io.FileDescriptor;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import java.util.function.Consumer;

@Command(name = "sqlgraph", mixinStandardHelpOptions = true, version = Main.VERSION,
        description = "DB 스키마와 소스 속 SQL 을 인덱싱하는 MCP 서버",
        subcommands = {Main.Serve.class, Main.Index.class, Main.Call.class, Main.Init.class})
public final class Main implements Runnable {

    public static final String VERSION = "0.1.0";

    public static void main(String[] args) {
        System.exit(new CommandLine(new Main()).execute(args));
    }

    @Override
    public void run() {
        new CommandLine(this).usage(System.err);
    }

    /** 공통 옵션 */
    static class Common {
        @Option(names = {"-c", "--config"}, description = "설정 파일 (기본: ./sqlgraph.yml 또는 환경변수 SQLGRAPH_CONFIG)")
        Path config;

        @Option(names = {"-p", "--project"}, description = "설정 파일 없이 실행할 때 프로젝트 폴더 (DB 없이 소스만)")
        Path project;

        @Option(names = {"-q", "--quiet"}, description = "진행 로그 숨김")
        boolean quiet;

        Config load() throws Exception {
            if (config != null) return Config.load(config);
            String env = System.getenv("SQLGRAPH_CONFIG");
            if (env != null && !env.isBlank()) return Config.load(Path.of(env));
            if (project == null && Files.isRegularFile(Path.of("sqlgraph.yml"))) return Config.load(Path.of("sqlgraph.yml"));
            if (project != null && Files.isRegularFile(project.resolve("sqlgraph.yml"))) {
                return Config.load(project.resolve("sqlgraph.yml"));
            }
            return Config.ofProject(project != null ? project : Path.of("."));
        }

        Consumer<String> logger() {
            return quiet ? s -> { } : s -> System.err.println("[sqlgraph] " + s);
        }
    }

    @Command(name = "serve", description = "MCP 서버 실행 (stdio). Claude Code 등에서 이 명령으로 띄웁니다")
    static class Serve implements Callable<Integer> {
        @CommandLine.Mixin Common common = new Common();

        @Option(names = "--reindex", description = "시작할 때 항상 다시 인덱싱 (기본: 인덱스가 없을 때만)")
        boolean reindex;

        @Override
        public Integer call() throws Exception {
            // stdout 은 MCP 프로토콜 전용. 실수로 찍히는 출력은 stderr 로 보낸다
            PrintStream protocolOut = new PrintStream(new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8);
            PrintStream err = new PrintStream(new FileOutputStream(FileDescriptor.err), true, StandardCharsets.UTF_8);
            System.setOut(err);
            System.setErr(err);   // MCP 클라이언트는 stderr 로그를 UTF-8 로 읽는다
            Consumer<String> log = common.logger();
            Config cfg = common.load();
            log.accept("config=" + (cfg.file() == null ? "(없음)" : cfg.file()) + ", project=" + cfg.project()
                    + ", index=" + cfg.index());
            ObjectMapper om = new ObjectMapper();
            try (SqlGraphService svc = new SqlGraphService(cfg, log)) {
                if (reindex || !svc.indexExists()) {
                    Thread t = new Thread(() -> {
                        try {
                            Indexer.Report r = svc.index();
                            log.accept("자동 인덱싱 완료: 테이블 " + r.tables() + ", 쿼리 " + r.statements());
                        } catch (Exception e) {
                            log.accept("자동 인덱싱 실패: " + e.getMessage());
                        }
                    }, "sqlgraph-index");
                    t.setDaemon(true);
                    t.start();
                }
                new McpServer(om, new Tools(svc, om), VERSION, log).run(System.in, protocolOut);
            }
            return 0;
        }
    }

    @Command(name = "index", description = "인덱싱만 실행")
    static class Index implements Callable<Integer> {
        @CommandLine.Mixin Common common = new Common();

        @Override
        public Integer call() throws Exception {
            Config cfg = common.load();
            try (SqlGraphService svc = new SqlGraphService(cfg, common.logger())) {
                System.out.println(new Tools(svc, new ObjectMapper()).reindex());
                System.out.println("인덱스 파일: " + cfg.index());
            }
            return 0;
        }
    }

    @Command(name = "call", description = "도구를 직접 호출 (테스트용). 예: sqlgraph call describe_table '{\"table\":\"HR_EMP\"}'")
    static class Call implements Callable<Integer> {
        @CommandLine.Mixin Common common = new Common();

        @Parameters(index = "0", description = "도구 이름")
        String tool;

        @Parameters(index = "1", arity = "0..1", description = "JSON 인자")
        String json;

        @Override
        public Integer call() throws Exception {
            Config cfg = common.load();
            ObjectMapper om = new ObjectMapper();
            try (SqlGraphService svc = new SqlGraphService(cfg, common.logger())) {
                if (!svc.indexExists()) svc.index();
                JsonNode args = json == null ? om.createObjectNode() : om.readTree(json);
                System.out.println(new Tools(svc, om).call(tool, args));
            }
            return 0;
        }
    }

    @Command(name = "init", description = "현재 폴더에 sqlgraph.yml 예시 파일 생성")
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
                # sqlgraph-mcp 설정 (상대 경로는 이 파일 위치 기준)
                project: .
                # index: .sqlgraph/graph.db        # 인덱스 파일 위치 (기본값)

                databases:
                  - id: main
                    type: jdbc
                    url: jdbc:mariadb://localhost:3306/mydb
                    user: reader
                    passwordEnv: SQLGRAPH_DB_PW    # 비밀번호는 환경변수로 (password: 로 직접 써도 됨)
                    # schema: HR                   # Oracle/PostgreSQL 등 스키마 지정
                    # driverJar: ./drivers/tibero7-jdbc.jar   # 내장되지 않은 드라이버
                    # driverClass: com.tmax.tibero.jdbc.TbDriver

                  # DB 에 접속할 수 없으면 DDL 파일로:
                  # - id: legacy
                  #   type: ddl
                  #   files: ["ddl/*.sql"]
                  #   dialect: oracle

                sources:
                  include: ["**/*.xml", "**/*.sql"]
                  exclude: ["**/test/**"]
                """;
    }
}
