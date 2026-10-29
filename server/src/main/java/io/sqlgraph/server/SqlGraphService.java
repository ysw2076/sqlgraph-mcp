package io.sqlgraph.server;

import io.sqlgraph.connector.DdlSchemaSource;
import io.sqlgraph.connector.JdbcSchemaSource;
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
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Stream;

/** 설정 → 커넥터/추출기 조립, 인덱싱 실행, 저장소 접근. */
public final class SqlGraphService implements AutoCloseable {

    private final Config config;
    private final Consumer<String> log;
    private GraphStore store;

    public SqlGraphService(Config config, Consumer<String> log) {
        this.config = config;
        this.log = log;
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

    public synchronized Indexer.Report index() throws Exception {
        List<SchemaSource> sources = new ArrayList<>();
        for (Config.Db db : config.databases()) sources.add(createSource(db));
        List<SqlExtractor> extractors = List.of(new MyBatisXmlExtractor(), new SqlFileExtractor());
        Indexer.Options opt = new Indexer.Options(config.project(), config.include(), config.exclude(),
                config.maxFileBytes());
        return new Indexer(log).run(sources, extractors, opt, store());
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
                    db.driverClass(), db.schema(), db.properties()));
        }
        if (db.type().equals("ddl")) {
            return new DdlSchemaSource(db.id(), expand(db.files()), db.dialect());
        }
        throw new IllegalArgumentException("[" + db.id() + "] 알 수 없는 type: " + db.type() + " (jdbc 또는 ddl)");
    }

    /** "ddl/*.sql" 같은 glob 을 설정 폴더 기준으로 펼친다 */
    private List<Path> expand(List<String> patterns) throws IOException {
        List<Path> out = new ArrayList<>();
        Path base = config.baseDir();
        for (String p : patterns) {
            Path direct = base.resolve(p).normalize();
            if (!p.contains("*") && !p.contains("?")) {
                if (Files.isRegularFile(direct)) out.add(direct);
                else log.accept("DDL 파일 없음: " + direct);
                continue;
            }
            String glob = p.replace('\\', '/');
            PathMatcher m = FileSystems.getDefault().getPathMatcher("glob:" + glob);
            int fixed = glob.indexOf('*');
            String prefix = glob.substring(0, Math.max(0, glob.lastIndexOf('/', fixed)));
            Path start = base.resolve(prefix.isEmpty() ? "." : prefix).normalize();
            if (!Files.isDirectory(start)) continue;
            try (Stream<Path> s = Files.walk(start)) {
                s.filter(Files::isRegularFile)
                        .filter(f -> m.matches(base.relativize(f)) || m.matches(Path.of(base.relativize(f).toString().replace('\\', '/'))))
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
