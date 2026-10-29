package io.sqlgraph.server;

import io.sqlgraph.connector.DriverResolver;

import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 여러 프로젝트를 관리한다. MCP 서버 하나로 "A 프로젝트 인덱싱해줘", "B 프로젝트의 HR_EMP 보여줘" 를 처리.
 *
 * <p>프로젝트를 지정하지 않은 도구 호출은 "현재 프로젝트"를 쓴다:
 * 마지막으로 인덱싱/조회한 프로젝트 → 서버를 띄운 폴더(등록돼 있으면) → 등록된 프로젝트가 하나뿐이면 그것.
 */
public final class Workspace implements AutoCloseable {

    private final SqlGraphHome home;
    private final ProjectRegistry registry;
    private final DriverResolver resolver;
    private final Consumer<String> log;
    private final Path cwd;
    private final Map<String, SqlGraphService> open = new LinkedHashMap<>();
    private Path current;
    /** serve -c 로 지정한 설정 (해당 프로젝트에만 적용) */
    private Config pinned;

    public Workspace(SqlGraphHome home, Path cwd, Consumer<String> log) {
        this.home = home;
        this.registry = new ProjectRegistry(home.registryFile());
        this.resolver = new DriverResolver(home.drivers(), log);
        this.log = log == null ? s -> { } : log;
        this.cwd = cwd.toAbsolutePath().normalize();
    }

    public Path cwd() {
        return cwd;
    }

    public ProjectRegistry registry() {
        return registry;
    }

    /** serve -c sqlgraph.yml 처럼 설정 파일을 명시했을 때 */
    public synchronized void pin(Config cfg) {
        this.pinned = cfg;
        this.current = cfg.project();
    }

    public synchronized Path current() {
        return current;
    }

    /**
     * 도구 인자의 project 값을 실제 경로로. null 이면 현재 프로젝트.
     *
     * @throws IllegalArgumentException 정할 수 없을 때 (사용자에게 그대로 보여줄 메시지)
     */
    public synchronized Path resolve(String arg) {
        if (arg != null && !arg.isBlank()) {
            String a = arg.strip();
            var byName = registry.findByName(a);
            if (byName.isPresent()) return byName.get().path();
            try {
                Path p = cwd.resolve(a).toAbsolutePath().normalize();
                if (Files.isDirectory(p)) return p;
            } catch (InvalidPathException ignore) {
                // 이름도 경로도 아님
            }
            throw new IllegalArgumentException("프로젝트 '" + a + "' 를 찾을 수 없습니다. 등록된 프로젝트: "
                    + names() + ". 새 프로젝트는 index_project 에 폴더 경로를 주세요");
        }
        if (current != null) return current;
        if (registry.find(cwd).isPresent() || Files.isRegularFile(cwd.resolve("sqlgraph.yml"))) return cwd;
        List<ProjectRegistry.Entry> all = registry.list();
        if (all.size() == 1) return all.get(0).path();
        if (all.isEmpty()) {
            throw new IllegalArgumentException("아직 인덱싱한 프로젝트가 없습니다. index_project 에 프로젝트 폴더 경로를 "
                    + "주세요 (현재 폴더: " + cwd + ")");
        }
        throw new IllegalArgumentException("프로젝트를 지정하세요 (project 인자). 등록된 프로젝트: " + names());
    }

    private String names() {
        List<ProjectRegistry.Entry> all = registry.list();
        if (all.isEmpty()) return "(없음)";
        return String.join(", ", all.stream().map(e -> e.name() + " (" + e.path() + ")").toList());
    }

    /** 프로젝트의 설정: 고정된 설정 → 등록된 설정 파일 → 프로젝트 루트의 sqlgraph.yml → 자동 탐지 */
    public Config configFor(Path project) throws Exception {
        if (pinned != null && SqlGraphHome.key(pinned.project()).equals(SqlGraphHome.key(project))) return pinned;
        Path defaultIndex = home.indexFor(project);
        var entry = registry.find(project);
        if (entry.isPresent() && entry.get().config() != null && Files.isRegularFile(entry.get().config())) {
            return Config.load(entry.get().config(), defaultIndex);
        }
        Path yml = project.resolve("sqlgraph.yml");
        if (Files.isRegularFile(yml)) return Config.load(yml, defaultIndex);
        return Config.ofProject(project, defaultIndex, null);
    }

    /** 이미 인덱싱된 프로젝트의 서비스 (조회용) */
    public synchronized SqlGraphService service(Path project) throws Exception {
        String key = SqlGraphHome.key(project);
        SqlGraphService s = open.get(key);
        if (s == null) {
            s = new SqlGraphService(configFor(project), log, resolver);
            if (!s.indexExists()) {
                throw new IllegalArgumentException("'" + project + "' 는 아직 인덱싱되지 않았습니다. "
                        + "index_project 를 먼저 실행하세요");
            }
            open.put(key, s);
        }
        current = project;
        return s;
    }

    public record Indexed(ProjectRegistry.Entry entry, Config config, SqlGraphService.Outcome outcome) {}

    /** 인덱싱 (처음이면 등록) */
    public synchronized Indexed index(Path project, String profile, boolean skipDb) throws Exception {
        if (!Files.isDirectory(project)) throw new IllegalArgumentException("폴더가 아닙니다: " + project);
        Config cfg = configFor(project).withProfile(profile);
        if (skipDb) cfg = cfg.withoutDatabases();
        String key = SqlGraphHome.key(project);
        SqlGraphService old = open.remove(key);
        if (old != null) old.close();
        SqlGraphService svc = new SqlGraphService(cfg, log, resolver);
        SqlGraphService.Outcome out = svc.indexWithDetails();
        open.put(key, svc);
        Path cfgFile = pinned != null && cfg.file() != null && cfg.file().equals(pinned.file()) ? pinned.file() : null;
        ProjectRegistry.Entry e = registry.upsert(project, cfgFile, Instant.now().toString());
        current = project;
        return new Indexed(e, cfg, out);
    }

    @Override
    public synchronized void close() throws Exception {
        for (SqlGraphService s : open.values()) s.close();
        open.clear();
    }
}
