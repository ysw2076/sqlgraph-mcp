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

    public record Report(int databases, int tables, int columns, int filesScanned, int filesWithSql,
                         int statements, int parsed, int failed, int dynamic, long millis,
                         List<String> warnings) {}

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

        // 1. DB 메타데이터
        List<SchemaSnapshot> snapshots = new ArrayList<>();
        for (SchemaSource src : sources) {
            try {
                log.accept("[db] " + src.id() + " 메타데이터 수집 중...");
                SchemaSnapshot s = src.load();
                snapshots.add(s);
                s.warnings().stream().limit(20).forEach(w -> warnings.add("[" + src.id() + "] " + w));
                log.accept("[db] " + src.id() + ": 테이블 " + s.tables().size() + "개");
            } catch (Exception e) {
                warnings.add("[" + src.id() + "] 메타데이터 수집 실패: " + e.getMessage());
                log.accept("[db] " + src.id() + " 실패: " + e.getMessage());
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
        store.replaceAll(snapshots, stmts, meta);

        return new Report(snapshots.size(), tables, columns, files[0], files[1], stmts.size(), parsed,
                stmts.size() - parsed, dynamic, millis, warnings);
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
