package io.sqlgraph.server;

import io.sqlgraph.connector.DdlSchemaSource;
import io.sqlgraph.connector.DriverResolver;
import io.sqlgraph.connector.JdbcSchemaSource;
import io.sqlgraph.connector.detect.DbConfigDetector;
import io.sqlgraph.connector.detect.DetectedDb;
import io.sqlgraph.core.index.Indexer;
import io.sqlgraph.core.semantic.EmbeddingIndexer;
import io.sqlgraph.core.semantic.HybridRanker;
import io.sqlgraph.core.semantic.VectorIndex;
import io.sqlgraph.core.spi.Embedder;
import io.sqlgraph.core.spi.SchemaSource;
import io.sqlgraph.core.spi.SqlExtractor;
import io.sqlgraph.core.store.GraphStore;
import io.sqlgraph.extractor.MyBatisXmlExtractor;
import io.sqlgraph.extractor.SqlFileExtractor;
import io.sqlgraph.extractor.code.CodeSqlExtractor;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.time.Duration;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
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
    private final EmbedderFactory embedders;
    /** 마지막으로 시작한 의미 검색 작업 (없으면 null) */
    private volatile SemanticJob job;

    /** 의미 검색 상태 */
    public enum SemanticState { OFF, NONE, PREPARING, RUNNING, READY, FAILED, CANCELLED }

    /**
     * @param model     READY 일 때 emb_model
     * @param docs      READY 일 때 emb 행 수
     * @param updatedAt READY 일 때 emb_updated_at
     */
    public record SemanticStatus(SemanticState state, int done, int total, String message,
                                 String model, int docs, String updatedAt) {}

    /** 같은 인덱스에 이전 작업이 끝나기를 기다리는 최대 시간 */
    private static final Duration STOP_WAIT = Duration.ofMinutes(2);

    public SqlGraphService(Config config, Consumer<String> log) {
        this(config, log, null);
    }

    public SqlGraphService(Config config, Consumer<String> log, DriverResolver resolver) {
        this(config, log, resolver, null);
    }

    /** @param embedders 의미 검색용 Embedder 공장 (null 이면 의미 검색 작업을 시작하지 않음) */
    public SqlGraphService(Config config, Consumer<String> log, DriverResolver resolver, EmbedderFactory embedders) {
        this.config = config;
        this.log = log == null ? s -> { } : log;
        this.resolver = resolver;
        this.embedders = embedders;
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
        stopSemantic();   // 같은 인덱스에 이전 임베딩 작업이 동시에 쓰지 않도록
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

        List<SqlExtractor> extractors = List.of(new MyBatisXmlExtractor(), new SqlFileExtractor(), new CodeSqlExtractor());
        Indexer.Options opt = new Indexer.Options(config.project(), config.include(), config.exclude(),
                config.maxFileBytes());
        Indexer.Report report = new Indexer(log).run(sources, extractors, opt, store());
        store().setMeta("databases", String.join("\n", dbLines));
        store().setMeta("detect_notes", String.join("\n", notes));
        Outcome outcome = new Outcome(report, dbLines, notes);
        if (semanticEnabled()) startSemantic();
        return outcome;
    }

    // ============================================================== 의미 검색 (백그라운드 임베딩)

    /** 설정에서 켜져 있고 공장이 있는가 */
    public boolean semanticEnabled() {
        return config.semantic() != null && config.semantic().enabled() && embedders != null;
    }

    private void startSemantic() {
        SemanticJob j = new SemanticJob();
        Thread t = new Thread(j::run, "sqlgraph-semantic");
        t.setDaemon(true);
        j.thread = t;
        job = j;
        t.start();
    }

    /** 돌고 있는 작업을 취소하고 끝날 때까지 기다린다 */
    private void stopSemantic() {
        SemanticJob j = job;
        if (j == null || j.thread == null || !j.thread.isAlive()) return;
        j.cancel.set(true);
        j.thread.interrupt();
        try {
            j.thread.join(STOP_WAIT.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (j.thread.isAlive()) log.accept("의미 검색 작업이 " + STOP_WAIT.toSeconds() + "초 안에 끝나지 않았습니다");
    }

    /**
     * 작업이 끝날 때까지 기다린다 (CLI·테스트용).
     *
     * @return 끝났으면(또는 작업이 없으면) true
     */
    public boolean awaitSemantic(Duration timeout) throws InterruptedException {
        SemanticJob j = job;
        if (j == null || j.thread == null) return true;
        j.thread.join(Math.max(1, timeout.toMillis()));
        return !j.thread.isAlive();
    }

    /** 작업이 있으면 그 상태, 없으면 저장된 임베딩으로 READY/NONE, 꺼져 있으면 OFF */
    public SemanticStatus semanticStatus() throws Exception {
        SemanticJob j = job;
        if (j != null) {
            SemanticState s = j.state;
            if (s == SemanticState.READY) return stored(SemanticState.READY);
            return new SemanticStatus(s, j.done, j.total, j.message, null, 0, null);
        }
        if (!config.semantic().enabled()) return new SemanticStatus(SemanticState.OFF, 0, 0, null, null, 0, null);
        if (!indexExists()) return new SemanticStatus(SemanticState.NONE, 0, 0, null, null, 0, null);
        return stored(null);
    }

    /** meta·emb 로 READY 정보. want 가 null 이면 데이터가 있을 때만 READY, 없으면 NONE */
    private SemanticStatus stored(SemanticState want) throws Exception {
        GraphStore st = store();
        Map<String, String> meta = st.meta();
        String model = meta.get("emb_model");
        int docs = st.embCount();
        if (want == null && (model == null || docs == 0)) {
            return new SemanticStatus(SemanticState.NONE, 0, 0, null, null, 0, null);
        }
        return new SemanticStatus(SemanticState.READY, docs, docs, null, model, docs, meta.get("emb_updated_at"));
    }

    /** 백그라운드 임베딩 작업 하나. 자기 GraphStore 연결을 열어 쓰고 닫는다 */
    private final class SemanticJob {
        final AtomicBoolean cancel = new AtomicBoolean();
        volatile Thread thread;
        volatile SemanticState state = SemanticState.PREPARING;
        volatile int done;
        volatile int total;
        volatile String message;

        void run() {
            Embedder embedder = null;
            try {
                if (cancel.get()) throw new CancellationException();
                embedder = embedders.open(config.semantic(), log);
                if (embedder == null) throw new IllegalStateException("Embedder 를 열지 못했습니다");
                if (cancel.get()) throw new CancellationException();
                state = SemanticState.RUNNING;
                try (GraphStore st = GraphStore.open(config.index())) {
                    EmbeddingIndexer.Result r = new EmbeddingIndexer().run(st, embedder, 32, cancel::get, (d, t) -> {
                        total = t;
                        done = d;
                    });
                    if (r.docs() == 0) {
                        log.accept("의미 검색: 테이블이 없어 임베딩을 건너뜁니다 (DB 연결 실패 등). 저장된 임베딩 캐시는 그대로 둡니다");
                    }
                    log.accept("의미 검색: 문서 " + r.docs() + " (새로 " + r.embedded() + ", 재사용 " + r.reused() + ")");
                }
                state = SemanticState.READY;
            } catch (Throwable e) {
                if (cancel.get() || e instanceof CancellationException) {
                    state = SemanticState.CANCELLED;
                    log.accept("의미 검색: 취소됨");
                } else {
                    message = describe(e);
                    state = SemanticState.FAILED;
                    log.accept("의미 검색 실패: " + message);
                }
            } finally {
                if (embedder instanceof AutoCloseable c) {
                    try {
                        c.close();
                    } catch (Exception e) {
                        log.accept("Embedder 닫기 실패: " + e.getMessage());
                    }
                }
            }
        }
    }

    private static String describe(Throwable e) {
        String m = e.getMessage();
        if (m == null || m.isBlank()) {
            Throwable c = e.getCause();
            m = c != null && c.getMessage() != null ? c.getMessage() : e.getClass().getSimpleName();
        }
        return m;
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
            int fixed = firstWildcard(glob);   // 와일드카드 앞까지만 Path 로 다룬다 (Windows)
            String head = glob.substring(0, Math.max(0, glob.lastIndexOf('/', fixed) + 1));
            boolean absolute = !head.isEmpty() && Path.of(head).isAbsolute();
            PathMatcher m = FileSystems.getDefault().getPathMatcher("glob:" + glob);
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

    private static int firstWildcard(String glob) {
        int i = -1;
        for (char c : new char[]{'*', '?', '[', '{'}) {
            int k = glob.indexOf(c);
            if (k >= 0 && (i < 0 || k < i)) i = k;
        }
        return i < 0 ? glob.length() : i;
    }

    // ============================================================== 의미 검색 (질의)

    /** 하이브리드 검색 결과 한 건. columnName 은 의미 점수가 컬럼 문서에서 왔을 때만 */
    public record HybridHit(GraphStore.TableHit table, HybridRanker.Ranked rank, String columnName) {}

    /** 질의용 Embedder (처음 의미 검색 때 한 번 열고 close 때 닫음) */
    private Embedder queryEmbedder;
    /** 질의용 Embedder 를 열다 실패한 이유 (다시 시도하지 않음) */
    private String queryEmbedderError;
    private VectorIndex vectors;
    private String vectorsStamp;

    private synchronized Embedder queryEmbedder() {
        if (queryEmbedder == null && queryEmbedderError == null) {
            try {
                queryEmbedder = embedders.open(config.semantic(), log);
            } catch (Exception e) {
                queryEmbedderError = describe(e);
                log.accept("의미 검색 질의용 모델을 열지 못했습니다: " + queryEmbedderError);
            }
        }
        return queryEmbedder;
    }

    /**
     * 의미 검색을 지금 쓸 수 있는가: 켜져 있고, 임베딩 작업이 돌고 있지 않고, 저장된 임베딩이 있고,
     * 그 모델이 질의용 모델과 같을 때. 처음 true 를 판단할 때 질의용 모델을 연다 (수 초).
     */
    public boolean semanticReady() throws Exception {
        if (!semanticEnabled()) return false;
        SemanticJob j = job;
        if (j != null && j.thread != null && j.thread.isAlive()) return false;
        GraphStore st = store();
        String model = st.meta().get("emb_model");
        if (model == null || st.embCount() == 0) return false;
        Embedder q = queryEmbedder();
        return q != null && model.equals(q.modelId());
    }

    /** 저장된 임베딩이 바뀌었으면 (emb_updated_at) 다시 읽는다 */
    private synchronized VectorIndex vectors() throws Exception {
        String stamp = store().meta().get("emb_updated_at");
        if (vectors == null || !java.util.Objects.equals(stamp, vectorsStamp)) {
            vectors = VectorIndex.load(store());
            vectorsStamp = stamp;
        }
        return vectors;
    }

    /**
     * 키워드 순위와 의미 순위를 합친 검색. 호출 전에 {@link #semanticReady()} 를 확인한다.
     *
     * @param semanticOnly true 면 키워드 순위 없이 의미만 (이름 일치는 그대로 맨 위)
     */
    public List<HybridHit> hybridSearch(String keyword, boolean includeColumns, int limit, boolean semanticOnly)
            throws Exception {
        Config.Semantic sem = config.semantic();
        GraphStore st = store();
        String q = keyword == null ? "" : keyword.strip();

        List<Long> kwIds = new ArrayList<>();
        if (!semanticOnly) {
            for (GraphStore.TableHit h : st.searchTables(q, includeColumns, sem.topK())) kwIds.add(h.id());
        }
        Set<Long> exact = new HashSet<>();
        if (!q.isEmpty() && !q.contains(" ")) {
            for (GraphStore.TableHit h : st.findTables(q, null)) exact.add(h.id());
        }

        List<VectorIndex.Hit> hits = List.of();
        float threshold = Float.POSITIVE_INFINITY;
        if (!q.isEmpty()) {
            float[] qv = queryEmbedder().embed(List.of(q), Embedder.Kind.QUERY)[0];
            hits = vectors().topK(qv, sem.topK());
            if (!hits.isEmpty()) threshold = (float) (hits.get(0).score() - sem.relativeThreshold());
        }

        List<HybridRanker.Ranked> ranked = HybridRanker.rank(kwIds, hits, exact, threshold, limit);
        List<GraphStore.TableHit> tables = st.tablesByIds(ranked.stream().map(HybridRanker.Ranked::tblId).toList());
        Map<Long, GraphStore.TableHit> byId = new java.util.HashMap<>();
        for (GraphStore.TableHit t : tables) byId.put(t.id(), t);
        List<HybridHit> out = new ArrayList<>();
        for (HybridRanker.Ranked r : ranked) {
            GraphStore.TableHit t = byId.get(r.tblId());
            if (t == null) continue;
            String col = r.bestColId() == null ? null : st.columnName(r.bestColId());
            out.add(new HybridHit(t, r, col));
        }
        return out;
    }

    @Override
    public synchronized void close() throws Exception {
        stopSemantic();
        if (queryEmbedder instanceof AutoCloseable c) {
            try {
                c.close();
            } catch (Exception e) {
                log.accept("질의용 Embedder 닫기 실패: " + e.getMessage());
            }
        }
        queryEmbedder = null;
        vectors = null;
        if (store != null) store.close();
    }
}
