package io.sqlgraph.server;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;

/**
 * Claude Code 에 MCP 서버로 등록/해제.
 * 지금 실행 중인 java 와 jar 경로로 serve 명령을 만들어 {@code claude mcp add --scope user} 로 등록한다.
 * (Windows 에서 .cmd 스크립트를 등록하면 cmd /c 가 필요하므로 java 실행 파일을 직접 등록)
 */
final class Installer {

    static final String SERVER_NAME = "sqlgraph";

    private Installer() {}

    /** MCP 클라이언트가 실행할 명령 (java + 옵션 + jar + serve) */
    static List<String> serveCommand() {
        String java = ProcessHandle.current().info().command()
                .orElse(Path.of(System.getProperty("java.home"), "bin", isWindows() ? "java.exe" : "java").toString());
        Path jar;
        try {
            jar = Path.of(Main.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        } catch (Exception e) {
            throw new IllegalStateException("실행 jar 경로를 알 수 없습니다", e);
        }
        if (!jar.toString().endsWith(".jar")) {
            throw new IllegalStateException("jar 로 실행해야 등록할 수 있습니다 (현재: " + jar + "). "
                    + "배포 파일의 bin/sqlgraph 또는 java -jar sqlgraph-mcp-*-all.jar 로 실행하세요");
        }
        return List.of(java, "--enable-native-access=ALL-UNNAMED", "-jar", jar.toAbsolutePath().toString(), "serve");
    }

    static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    /** PATH 에서 claude CLI 찾기 */
    static Path findClaude() {
        String path = System.getenv("PATH");
        if (path == null) return null;
        List<String> names = isWindows() ? List.of("claude.exe", "claude.cmd", "claude.bat", "claude") : List.of("claude");
        for (String dir : path.split(File.pathSeparator)) {
            if (dir.isBlank()) continue;
            for (String n : names) {
                Path p = Path.of(dir.strip().replace("\"", ""), n);
                if (Files.isRegularFile(p) && (isWindows() || Files.isExecutable(p))) return p;
            }
        }
        return null;
    }

    static List<String> claudeCmd(Path claude, List<String> args) {
        List<String> c = new ArrayList<>();
        String n = claude.getFileName().toString().toLowerCase(Locale.ROOT);
        if (isWindows() && (n.endsWith(".cmd") || n.endsWith(".bat"))) {
            c.add("cmd");
            c.add("/c");
        }
        c.add(claude.toString());
        c.addAll(args);
        return c;
    }

    static int run(List<String> cmd, boolean show) throws Exception {
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!p.waitFor(60, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            return -1;
        }
        if (show && !out.isBlank()) System.out.println(out.strip());
        return p.exitValue();
    }

    static String quote(String s) {
        return s.contains(" ") ? "\"" + s + "\"" : s;
    }

    @Command(name = "install", description = "Claude Code 에 MCP 서버로 등록 (모든 프로젝트에서 사용)")
    static class Install implements Callable<Integer> {
        @Option(names = "--scope", defaultValue = "user", description = "user(기본, 모든 프로젝트) / local / project")
        String scope;

        @Option(names = "--dry-run", description = "실행하지 않고 명령만 출력")
        boolean dryRun;

        @Override
        public Integer call() throws Exception {
            List<String> serve = serveCommand();
            List<String> addArgs = new ArrayList<>(List.of("mcp", "add", "--scope", scope, SERVER_NAME, "--"));
            addArgs.addAll(serve);
            Path claude = findClaude();
            String manual = "claude " + String.join(" ", addArgs.stream().map(Installer::quote).toList());
            if (dryRun) {
                System.out.println(manual);
                return 0;
            }
            if (claude == null) {
                System.out.println("""
                        Claude Code CLI(claude)를 PATH 에서 찾지 못했습니다.
                        Claude Code 를 설치한 뒤 아래 명령을 실행하거나, 다시 'sqlgraph install' 을 실행하세요:

                          %s

                        Claude Desktop 등 다른 MCP 클라이언트는 설정 파일의 mcpServers 에 아래처럼 추가합니다:
                          "%s": { "command": "%s", "args": [%s] }
                        """.formatted(manual, SERVER_NAME, serve.get(0).replace("\\", "\\\\"),
                        String.join(", ", serve.subList(1, serve.size()).stream()
                                .map(a -> "\"" + a.replace("\\", "\\\\") + "\"").toList())));
                return 1;
            }
            // 이미 등록돼 있으면 경로가 바뀌었을 수 있으니 지우고 다시 등록
            run(claudeCmd(claude, List.of("mcp", "remove", SERVER_NAME, "--scope", scope)), false);
            int code = run(claudeCmd(claude, addArgs), true);
            if (code != 0) {
                System.out.println("등록에 실패했습니다. 직접 실행해 보세요:\n  " + manual);
                return code;
            }
            System.out.println("""

                    Claude Code 에 '%s' MCP 서버를 등록했습니다 (scope: %s).
                    Claude Code 를 새로 열고 /mcp 에서 확인한 뒤, 이렇게 말해 보세요:
                      "C:\\work\\my-project DB 인덱싱해줘"
                    """.formatted(SERVER_NAME, scope));
            return 0;
        }
    }

    @Command(name = "uninstall", description = "Claude Code 에서 MCP 서버 등록 해제 (인덱스는 ~/.sqlgraph 에 남음)")
    static class Uninstall implements Callable<Integer> {
        @Option(names = "--scope", defaultValue = "user", description = "등록할 때 쓴 scope")
        String scope;

        @Override
        public Integer call() throws Exception {
            Path claude = findClaude();
            if (claude == null) {
                System.out.println("claude CLI 를 찾지 못했습니다. 직접 실행하세요: claude mcp remove " + SERVER_NAME
                        + " --scope " + scope);
                return 1;
            }
            int code = run(claudeCmd(claude, List.of("mcp", "remove", SERVER_NAME, "--scope", scope)), true);
            if (code == 0) {
                System.out.println("등록을 해제했습니다. 인덱스와 다운로드한 드라이버는 " + SqlGraphHome.fromEnv().dir()
                        + " 에 남아 있습니다 (필요 없으면 지우세요).");
            }
            return code;
        }
    }
}
