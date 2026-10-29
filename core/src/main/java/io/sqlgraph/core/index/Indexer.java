package io.sqlgraph.core.index;

import io.sqlgraph.core.Names;
import io.sqlgraph.core.TextFiles;
import io.sqlgraph.core.analyze.SqlAnalyzer;
import io.sqlgraph.core.model.AnalyzedSql;
import io.sqlgraph.core.model.ColumnRef;
import io.sqlgraph.core.model.DbColumn;
import io.sqlgraph.core.model.DbTable;
import io.sqlgraph.core.model.ExtractedSql;
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

        // 2. 소스 스캔
        List<IndexedStmt> stmts = new ArrayList<>();
        int[] files = {0, 0};
        if (opt.projectRoot() != null && Files.isDirectory(opt.projectRoot())) {
            Path root = opt.projectRoot().toAbsolutePath().normalize();
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
                    if (applicable.isEmpty()) return FileVisitResult.CONTINUE;
                    files[0]++;
                    try {
                        String content = TextFiles.read(file);
                        boolean found = false;
                        for (SqlExtractor x : applicable) {
                            for (ExtractedSql es : x.extract(relStr, content)) {
                                stmts.add(new IndexedStmt(es, resolve(analyzer.analyze(es.sql()), columnIndex)));
                                found = true;
                            }
                        }
                        if (found) files[1]++;
                    } catch (Exception e) {
                        warnings.add("[src] " + relStr + ": " + e.getMessage());
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    return FileVisitResult.CONTINUE;
                }
            });
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

    /**
     * 테이블이 지정되지 않은 컬럼(WHERE EMP_NO = ? 처럼)을 DB 메타데이터로 해석한다.
     * 쿼리의 테이블 중 그 컬럼을 가진 테이블이 하나뿐이면 그 테이블로 확정한다.
     */
    static AnalyzedSql resolve(AnalyzedSql a, Map<String, Set<String>> columnIndex) {
        if (columnIndex.isEmpty() || a.columns().isEmpty()) return a;
        List<String> stmtTables = a.tables().stream().map(TableRef::name).distinct().toList();
        List<ColumnRef> out = new ArrayList<>(a.columns().size());
        boolean changed = false;
        for (ColumnRef c : a.columns()) {
            if (c.table() != null) {
                out.add(c);
                continue;
            }
            String ck = Names.key(c.column());
            String found = null;
            int hits = 0;
            for (String t : stmtTables) {
                Set<String> cols = columnIndex.get(Names.key(t));
                if (cols != null && cols.contains(ck)) {
                    found = t;
                    hits++;
                }
            }
            if (hits == 1) {
                out.add(c.withTable(found));
                changed = true;
            } else {
                out.add(c);
            }
        }
        return changed ? new AnalyzedSql(a.kind(), a.parsed(), a.error(), a.tables(), out, a.selectsAll()) : a;
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
