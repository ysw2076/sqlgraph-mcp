package io.sqlgraph.core.index;

import io.sqlgraph.core.Names;
import io.sqlgraph.core.TextFiles;
import io.sqlgraph.core.analyze.SqlAnalyzer;
import io.sqlgraph.core.model.AnalyzedSql;
import io.sqlgraph.core.model.ColumnRef;
import io.sqlgraph.core.model.DbColumn;
import io.sqlgraph.core.model.DbTable;
import io.sqlgraph.core.model.ExtractedSql;
import io.sqlgraph.core.model.JoinRef;
import io.sqlgraph.core.model.SchemaSnapshot;
import io.sqlgraph.core.model.TableRef;
import io.sqlgraph.core.spi.SchemaSource;
import io.sqlgraph.core.spi.SqlExtractor;
import io.sqlgraph.core.store.GraphStore;
import io.sqlgraph.core.store.GraphStore.IndexedStmt;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/** DB 메타데이터 수집 → 소스 스캔 → SQL 분석 → 저장 까지 전체 인덱싱을 수행한다. */
public final class Indexer {

    public record Options(Path projectRoot, List<String> include, List<String> exclude, long maxFileBytes) {}

    /**
     * @param staleDatabases 이번에 메타데이터를 못 읽어 이전 인덱스의 메타데이터를 그대로 쓴 DB
     *                       ("id (테이블 N개, 마지막 성공 시각)")
     */
    public record Report(int databases, int tables, int columns, int filesScanned, int filesWithSql,
                         int statements, int parsed, int failed, int dynamic, long millis,
                         List<String> warnings, List<String> staleDatabases) {}

    /** meta 키: DB 별 마지막으로 메타데이터를 읽은 시각 ("db_loaded_at." + db id) */
    public static final String META_DB_LOADED_AT = "db_loaded_at.";

    /** meta 키: 이전 메타데이터를 쓰고 있는 DB 목록 (줄마다 하나, 없으면 빈 문자열) */
    public static final String META_STALE_DATABASES = "stale_databases";

    /** 기본으로 건너뛰는 폴더 */
    private static final Set<String> SKIP_DIRS = Set.of(
            ".git", ".svn", ".hg", ".idea", ".vscode", ".gradle", ".sqlgraph", "node_modules",
            "build", "target", "out", "bin", "dist", ".settings");

    private final SqlAnalyzer analyzer = new SqlAnalyzer();
    private final Consumer<String> log;

    public Indexer(Consumer<String> log) {
        this.log = log == null ? s -> {} : log;
    }

    public Report run(List<SchemaSource> sources, List<SqlExtractor> extractors, Options opt,
                      GraphStore store) throws Exception {
        Instant start = Instant.now();
        List<String> warnings = new ArrayList<>();

        // 1. DB 메타데이터. 못 읽은 DB 는 이전 인덱스에 있던 메타데이터를 그대로 쓴다
        //    (사내망이 잠깐 끊긴 때 다시 인덱싱해도 테이블·컬럼이 0 으로 덮어써지지 않도록)
        String now = Instant.now().toString();
        Map<String, String> prevMeta = previousMeta(store);
        Map<String, String> loadedAt = new LinkedHashMap<>();
        List<String> stale = new ArrayList<>();
        List<SchemaSnapshot> snapshots = new ArrayList<>();
        for (SchemaSource src : sources) {
            try {
                log.accept("[db] " + src.id() + " 메타데이터 수집 중...");
                SchemaSnapshot s = src.load();
                snapshots.add(s);
                loadedAt.put(src.id(), now);
                s.warnings().stream().limit(20).forEach(w -> warnings.add("[" + src.id() + "] " + w));
                log.accept("[db] " + src.id() + ": 테이블 " + s.tables().size() + "개");
            } catch (Exception e) {
                SchemaSnapshot prev = previousSnapshot(store, src.id());
                if (prev == null) {
                    warnings.add("[" + src.id() + "] 메타데이터 수집 실패: " + e.getMessage());
                    log.accept("[db] " + src.id() + " 실패: " + e.getMessage());
                    continue;
                }
                // 이 키가 없는 인덱스(이 기능 이전)는 직전 인덱싱 때 읽은 것이다: 그때 실패했다면 테이블이 없었다
                String at = prevMeta.getOrDefault(META_DB_LOADED_AT + src.id(),
                        prevMeta.getOrDefault("indexed_at", "알 수 없음"));
                snapshots.add(prev);
                loadedAt.put(src.id(), at);
                stale.add(src.id() + " (테이블 " + prev.tables().size() + "개, 마지막 성공 " + at + ")");
                warnings.add("[" + src.id() + "] 메타데이터 수집 실패: " + e.getMessage()
                        + " → 이전 메타데이터 유지 (마지막 성공 " + at + ")");
                log.accept("[db] " + src.id() + " 실패: " + e.getMessage() + " → 이전 메타데이터 유지 (테이블 "
                        + prev.tables().size() + "개, 마지막 성공 " + at + ")");
            }
        }
        Map<String, Set<String>> columnIndex = buildColumnIndex(snapshots);
        Map<String, Set<String>> pkIndex = buildPkIndex(snapshots);

        // 2. 소스 스캔: 대상 파일 목록 → (필요하면) 파일 간 참조 수집 → 추출·분석
        List<IndexedStmt> stmts = new ArrayList<>();
        int[] files = {0, 0};
        if (opt.projectRoot() != null && Files.isDirectory(opt.projectRoot())) {
            Path root = opt.projectRoot().toAbsolutePath().normalize();
            List<SourceFile> targets = collect(root, extractors, opt);
            files[0] = targets.size();

            for (SourceFile f : targets) {
                List<SqlExtractor> pre = f.extractors().stream().filter(SqlExtractor::needsPrepare).toList();
                if (pre.isEmpty()) continue;
                try {
                    String content = TextFiles.read(f.path());
                    for (SqlExtractor x : pre) x.prepare(f.rel(), content);
                } catch (Exception e) {
                    warnings.add("[src] " + f.rel() + ": " + e.getMessage());
                }
            }

            for (SourceFile f : targets) {
                try {
                    String content = TextFiles.read(f.path());
                    boolean found = false;
                    for (SqlExtractor x : f.extractors()) {
                        for (ExtractedSql es : x.extract(f.rel(), content)) {
                            stmts.add(new IndexedStmt(es, resolve(analyze(es), columnIndex, pkIndex)));
                            found = true;
                        }
                    }
                    if (found) files[1]++;
                } catch (Exception e) {
                    warnings.add("[src] " + f.rel() + ": " + e.getMessage());
                }
            }
        } else {
            warnings.add("project 경로가 없거나 폴더가 아닙니다: " + opt.projectRoot());
        }
        log.accept("[src] 파일 " + files[0] + "개 스캔, 쿼리 " + stmts.size() + "건");

        // 3. 저장
        int parsed = (int) stmts.stream().filter(s -> s.analysis().parsed()).count();
        int dynamic = (int) stmts.stream().filter(s -> s.source().dynamic()).count();
        int tables = snapshots.stream().mapToInt(s -> s.tables().size()).sum();
        int columns = snapshots.stream().flatMap(s -> s.tables().stream()).mapToInt(t -> t.columns().size()).sum();
        long millis = Duration.between(start, Instant.now()).toMillis();

        Map<String, String> meta = new LinkedHashMap<>();
        meta.put("indexed_at", Instant.now().toString());
        meta.put("project_root", String.valueOf(opt.projectRoot()));
        meta.put("index_millis", String.valueOf(millis));
        meta.put("warnings", String.join("\n", warnings.stream().limit(50).toList()));
        loadedAt.forEach((id, at) -> meta.put(META_DB_LOADED_AT + id, at));
        meta.put(META_STALE_DATABASES, String.join("\n", stale));
        store.replaceAll(snapshots, stmts, meta);

        return new Report(snapshots.size(), tables, columns, files[0], files[1], stmts.size(), parsed,
                stmts.size() - parsed, dynamic, millis, warnings, List.copyOf(stale));
    }

    private Map<String, String> previousMeta(GraphStore store) {
        try {
            return store.meta();
        } catch (Exception e) {
            log.accept("[db] 이전 인덱스 정보를 읽지 못함: " + e.getMessage());
            return Map.of();
        }
    }

    /** 이전 인덱스에 있던 그 DB 의 메타데이터 (없거나 읽지 못하면 null) */
    private SchemaSnapshot previousSnapshot(GraphStore store, String dbId) {
        try {
            return store.loadSnapshot(dbId);
        } catch (Exception e) {
            log.accept("[db] " + dbId + " 이전 메타데이터를 읽지 못함: " + e.getMessage());
            return null;
        }
    }

    private record SourceFile(Path path, String rel, List<SqlExtractor> extractors) {}

    /** 추출기가 처리할 수 있는 파일 목록 (건너뛸 폴더, include/exclude, 크기 제한 적용) */
    private static List<SourceFile> collect(Path root, List<SqlExtractor> extractors, Options opt) throws IOException {
        List<SourceFile> out = new ArrayList<>();
        List<PathMatcher> inc = matchers(opt.include());
        List<PathMatcher> exc = matchers(opt.exclude());
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                if (!dir.equals(root)) {
                    String name = dir.getFileName().toString();
                    if (SKIP_DIRS.contains(name)) return FileVisitResult.SKIP_SUBTREE;
                    if (matchesAny(exc, root.relativize(dir))) return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                Path rel = root.relativize(file);
                if (!inc.isEmpty() && !matchesAny(inc, rel)) return FileVisitResult.CONTINUE;
                if (matchesAny(exc, rel)) return FileVisitResult.CONTINUE;
                if (attrs.size() > opt.maxFileBytes()) return FileVisitResult.CONTINUE;
                String relStr = rel.toString().replace('\\', '/');
                List<SqlExtractor> applicable = extractors.stream().filter(x -> x.supports(relStr)).toList();
                if (!applicable.isEmpty()) out.add(new SourceFile(file, relStr, applicable));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException e) {
                return FileVisitResult.CONTINUE;
            }
        });
        return out;
    }

    /** 본 SQL 과 다른 분기(variants)를 각각 분석해 합친다 */
    private AnalyzedSql analyze(ExtractedSql es) {
        AnalyzedSql a = analyzer.analyze(es.sql());
        for (String v : es.variants()) a = a.merge(analyzer.analyze(v));
        return a;
    }

    /** 테이블명(키) → 컬럼명(키) 집합. 여러 DB 에 같은 이름이 있으면 합친다. */
    private static Map<String, Set<String>> buildColumnIndex(List<SchemaSnapshot> snapshots) {
        Map<String, Set<String>> idx = new HashMap<>();
        for (SchemaSnapshot s : snapshots) {
            for (DbTable t : s.tables()) {
                Set<String> cols = idx.computeIfAbsent(Names.key(t.name()), k -> new HashSet<>());
                for (DbColumn c : t.columns()) cols.add(Names.key(c.name()));
            }
        }
        return idx;
    }

    /** 테이블명(키) → PK 컬럼명(키) 집합 */
    private static Map<String, Set<String>> buildPkIndex(List<SchemaSnapshot> snapshots) {
        Map<String, Set<String>> idx = new HashMap<>();
        for (SchemaSnapshot s : snapshots) {
            for (DbTable t : s.tables()) {
                for (DbColumn c : t.columns()) {
                    if (c.primaryKey()) idx.computeIfAbsent(Names.key(t.name()), k -> new HashSet<>()).add(Names.key(c.name()));
                }
            }
        }
        return idx;
    }

    /**
     * 1) 테이블이 지정되지 않은 컬럼(WHERE EMP_NO = ? 처럼)을 DB 메타데이터로 해석한다.
     *    쿼리의 테이블 중 그 컬럼을 가진 테이블이 하나뿐이면 그 테이블로 확정한다.
     * 2) 조인 관계를 정리한다: 양쪽 테이블이 이 쿼리의 실제 테이블이어야 하고(서브쿼리 별칭 제외),
     *    "컬럼 = 상수" 조건은 그 컬럼이 테이블 PK 의 일부일 때만 남긴다 (공통코드 GRP_CD='POS' 패턴).
     */
    static AnalyzedSql resolve(AnalyzedSql a, Map<String, Set<String>> columnIndex, Map<String, Set<String>> pkIndex) {
        List<String> stmtTables = a.tables().stream().map(TableRef::name).distinct().toList();
        List<ColumnRef> cols = a.columns();
        if (!columnIndex.isEmpty() && !cols.isEmpty()) {
            List<ColumnRef> out = new ArrayList<>(cols.size());
            for (ColumnRef c : cols) {
                String t = c.table() != null ? null : uniqueOwner(c.column(), stmtTables, columnIndex);
                out.add(t == null ? c : c.withTable(t));
            }
            cols = out;
        }
        Set<String> real = new HashSet<>();
        for (String t : stmtTables) real.add(Names.key(t));
        List<JoinRef> joins = new ArrayList<>();
        for (JoinRef j : a.joins()) {
            String lt = j.leftTable() != null ? j.leftTable() : uniqueOwner(j.leftColumn(), stmtTables, columnIndex);
            String rt = j.rightTable() != null ? j.rightTable() : uniqueOwner(j.rightColumn(), stmtTables, columnIndex);
            if (lt == null || rt == null) continue;
            if (!real.contains(Names.key(lt)) || !real.contains(Names.key(rt))) continue;
            boolean bothUnqualified = j.leftTable() == null && j.rightTable() == null;
            if (Names.key(lt).equals(Names.key(rt)) && bothUnqualified) continue;   // 한 테이블 안의 컬럼 비교
            joins.add(new JoinRef(lt, j.leftColumn(), rt, j.rightColumn(),
                    pkLiterals(lt, j.leftColumn(), j.leftLiterals(), pkIndex),
                    pkLiterals(rt, j.rightColumn(), j.rightLiterals(), pkIndex)));
        }
        return a.withColumnsAndJoins(cols, joins);
    }

    private static String uniqueOwner(String column, List<String> tables, Map<String, Set<String>> columnIndex) {
        String ck = Names.key(column);
        String found = null;
        int hits = 0;
        for (String t : tables) {
            Set<String> cs = columnIndex.get(Names.key(t));
            if (cs != null && cs.contains(ck)) {
                found = t;
                hits++;
            }
        }
        return hits == 1 ? found : null;
    }

    private static List<String> pkLiterals(String table, String joinCol, List<String> lits, Map<String, Set<String>> pkIndex) {
        Set<String> pk = pkIndex.get(Names.key(table));
        if (pk == null || lits.isEmpty()) return List.of();
        String jc = Names.key(joinCol);
        List<String> out = new ArrayList<>();
        for (String l : lits) {
            String col = l.substring(0, l.indexOf('='));
            if (!col.equals(jc) && pk.contains(col) && !out.contains(l)) out.add(l);
        }
        return out;
    }

    private static List<PathMatcher> matchers(List<String> globs) {
        List<PathMatcher> r = new ArrayList<>();
        if (globs == null) return r;
        for (String g : globs) {
            String p = g.replace('\\', '/');
            r.add(FileSystems.getDefault().getPathMatcher("glob:" + p));
            // "**/*.xml" 이 루트 바로 아래 파일도 잡도록
            if (p.startsWith("**/")) r.add(FileSystems.getDefault().getPathMatcher("glob:" + p.substring(3)));
        }
        return r;
    }

    private static boolean matchesAny(List<PathMatcher> ms, Path rel) {
        for (PathMatcher m : ms) if (m.matches(rel)) return true;
        return false;
    }
}
