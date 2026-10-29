package io.sqlgraph.server;

import io.sqlgraph.connector.DdlSchemaSource;
import io.sqlgraph.connector.DriverResolver;
import io.sqlgraph.connector.JdbcSchemaSource;
import io.sqlgraph.connector.detect.DbConfigDetector;
import io.sqlgraph.connector.detect.DetectedDb;
import io.sqlgraph.core.index.Indexer;
import io.sqlgraph.core.spi.SchemaSource;
import io.sqlgraph.core.spi.SqlExtractor;
import io.sqlgraph.core.store.GraphStore;
import io.sqlgraph.extractor.MyBatisXmlExtractor;
import io.sqlgraph.extractor.SqlFileExtractor;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** 프로젝트 하나: 설정 → 커넥터/추출기 조립, 인덱싱 실행, 저장소 접근. */
public final class SqlGraphService implements AutoCloseable {

    /** 인덱싱 결과 + 어떤 DB 를 어떻게 찾았는지 (비밀번호 제외) */
    public record Outcome(Indexer.Report report, List<String> databases, List<String> notes) {}

    private static final Pattern DB_NAME = Pattern.compile(
            "(?i)(?:/|databaseName=|:)([A-Za-z_][\\w$-]*)(?:[?;]|$)");

    private final Config config;
    private final Consumer<String> log;
    private final DriverResolver resolver;
    private GraphStore store;

    public SqlGraphService(Config config, Consumer<String> log) {
        this(config, log, null);
    }

    public SqlGraphService(Config config, Consumer<String> log, DriverResolver resolver) {
        this.config = config;
        this.log = log == null ? s -> { } : log;
        this.resolver = resolver;
    }

    public Config config() {
        return config;
    }

    public synchronized GraphStore store() throws Exception {
        if (store == null) store = GraphStore.open(config.index());
        return store;
    }

    public boolean indexExists() {
        return GraphStore.exists(config.index());
    }

    /** 이전 버전과의 호환용 */
    public Indexer.Report index() throws Exception {
        return indexWithDetails().report();
    }

    public synchronized Outcome indexWithDetails() throws Exception {
        List<SchemaSource> sources = new ArrayList<>();
        List<String> dbLines = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        Set<String> ids = new HashSet<>();

        for (Config.Db db : config.databases()) {
            sources.add(createSource(db));
            ids.add(db.id());
            dbLines.add(db.id() + ": " + (db.type().equals("jdbc") ? mask(db.url()) : "DDL " + db.files())
                    + " ← " + (config.file() == null ? "설정" : config.file().getFileName()));
        }

        if (config.autoDetect()) {
            DbConfigDetector.Result r = new DbConfigDetector().detect(config.project(), config.profile());
            notes.addAll(r.notes());
            for (DetectedDb d : r.all()) {
                if (r.chosen().contains(d)) continue;
                if (d.profile() != null && r.chosen().stream().anyMatch(c -> c.group().equals(d.group()))) {
                    notes.add("다른 프로필이라 사용하지 않음: " + d.describe().replaceAll(" ※ .*$", ""));
                } else if (d.problem() != null) {
                    notes.add("설정을 쓸 수 없음: " + d.describe());
                }
            }
            if (r.chosen().isEmpty() && config.databases().isEmpty()) {
                notes.add("DB 접속 설정을 찾지 못해 소스의 쿼리만 인덱싱했습니다. "
                        + "프로젝트 루트에 sqlgraph.yml 을 두면 DB 를 직접 지정할 수 있습니다");
            }
            for (DetectedDb d : r.chosen()) {
                String id = uniqueId(dbIdFromUrl(d.url()), ids);
                String pw = d.password();
                String envPw = System.getenv("SQLGRAPH_DB_PW_" + id.toUpperCase().replaceAll("\\W", "_"));
                if (envPw == null) envPw = System.getenv("SQLGRAPH_DB_PW");
                if (envPw != null) pw = envPw;
                sources.add(new JdbcSchemaSource(new JdbcSchemaSource.Settings(id, d.url(), d.user(), pw, null, null,
                        null, Map.of()), resolver));
                dbLines.add(id + ": " + d.describe() + " (자동 탐지)");
            }
        }

        List<SqlExtractor> extractors = List.of(new MyBatisXmlExtractor(), new SqlFileExtractor());
        Indexer.Options opt = new Indexer.Options(config.project(), config.include(), config.exclude(),
                config.maxFileBytes());
        Indexer.Report report = new Indexer(log).run(sources, extractors, opt, store());
        store().setMeta("databases", String.join("\n", dbLines));
        store().setMeta("detect_notes", String.join("\n", notes));
        return new Outcome(report, dbLines, notes);
    }

    private SchemaSource createSource(Config.Db db) throws IOException {
        if (db.type().equals("jdbc")) {
            if (db.url() == null) throw new IllegalArgumentException("[" + db.id() + "] url 이 없습니다");
            String pw = db.password();
            if (db.passwordEnv() != null) {
                String env = System.getenv(db.passwordEnv());
                if (env == null) log.accept("[" + db.id() + "] 환경변수 " + db.passwordEnv() + " 가 비어 있습니다");
                else pw = env;
            }
            Path jar = db.driverJar() == null ? null : config.baseDir().resolve(db.driverJar()).normalize();
            return new JdbcSchemaSource(new JdbcSchemaSource.Settings(db.id(), db.url(), db.user(), pw, jar,
                    db.driverClass(), db.schema(), db.properties()), resolver);
        }
        if (db.type().equals("ddl")) {
            return new DdlSchemaSource(db.id(), expand(db.files()), db.dialect());
        }
        throw new IllegalArgumentException("[" + db.id() + "] 알 수 없는 type: " + db.type() + " (jdbc 또는 ddl)");
    }

    static String dbIdFromUrl(String url) {
        if (url == null) return "db";
        String u = url.replaceFirst("^jdbc:[^:]+:(thin:@)?", "");
        Matcher m = DB_NAME.matcher(u);
        String last = null;
        while (m.find()) last = m.group(1);
        return last == null ? "db" : last.toLowerCase();
    }

    private static String uniqueId(String base, Set<String> used) {
        String id = base;
        int i = 2;
        while (used.contains(id)) id = base + i++;
        used.add(id);
        return id;
    }

    private static String mask(String url) {
        return url == null ? null : url.replaceAll("(?i)(password|pwd)=[^&;]*", "$1=****");
    }

    /** "ddl/*.sql" 같은 glob 을 설정 폴더 기준으로 펼친다 */
    private List<Path> expand(List<String> patterns) throws IOException {
        List<Path> out = new ArrayList<>();
        Path base = config.baseDir();
        for (String p : patterns) {
            if (!p.contains("*") && !p.contains("?")) {
                // Windows 는 * ? 가 든 문자열을 Path 로 만들 수 없으므로 glob 이 아닐 때만 Path 로 변환
                Path direct = base.resolve(p).normalize();
                if (Files.isRegularFile(direct)) out.add(direct);
                else log.accept("DDL 파일 없음: " + direct);
                continue;
            }
            String glob = p.replace('\\', '/');
            boolean absolute = Path.of(glob.substring(0, Math.max(1, glob.indexOf('*')))).isAbsolute();
            PathMatcher m = FileSystems.getDefault().getPathMatcher("glob:" + glob);
            int fixed = glob.indexOf('*');
            String prefix = glob.substring(0, Math.max(0, glob.lastIndexOf('/', fixed)));
            Path start = base.resolve(prefix.isEmpty() ? "." : prefix).normalize();
            if (!Files.isDirectory(start)) continue;
            try (Stream<Path> s = Files.walk(start)) {
                s.filter(Files::isRegularFile)
                        .filter(f -> {
                            Path rel = absolute ? f : base.relativize(f);
                            return m.matches(rel) || m.matches(Path.of(rel.toString().replace('\\', '/')));
                        })
                        .sorted()
                        .forEach(out::add);
            }
        }
        return out;
    }

    @Override
    public synchronized void close() throws Exception {
        if (store != null) store.close();
    }
}
