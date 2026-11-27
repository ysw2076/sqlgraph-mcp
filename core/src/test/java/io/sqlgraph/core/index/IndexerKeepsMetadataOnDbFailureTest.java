package io.sqlgraph.core.index;

import io.sqlgraph.core.model.DbColumn;
import io.sqlgraph.core.model.DbTable;
import io.sqlgraph.core.model.ExtractedSql;
import io.sqlgraph.core.model.ForeignKey;
import io.sqlgraph.core.model.SchemaSnapshot;
import io.sqlgraph.core.model.SourceLocation;
import io.sqlgraph.core.semantic.EmbeddingIndexer;
import io.sqlgraph.core.spi.Embedder;
import io.sqlgraph.core.spi.SchemaSource;
import io.sqlgraph.core.spi.SqlExtractor;
import io.sqlgraph.core.store.GraphStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.SocketTimeoutException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 다시 인덱싱할 때 DB 메타데이터를 못 읽으면(사내망 끊김 등) 그 DB 는 이전 인덱스의 테이블·컬럼·FK 를
 * 그대로 쓰고, 소스 쿼리만 새로 반영한다. 처음 인덱싱에서 실패하면 지금처럼 테이블 0 으로 진행한다.
 */
class IndexerKeepsMetadataOnDbFailureTest {

    @TempDir
    Path dir;

    /** fail 이 true 면 접속 시간 초과처럼 실패하는 SchemaSource */
    static final class FlakySource implements SchemaSource {
        final String id;
        List<DbTable> tables;
        List<ForeignKey> fks = List.of();
        boolean fail;

        FlakySource(String id, List<DbTable> tables) {
            this.id = id;
            this.tables = tables;
        }

        @Override public String id() { return id; }

        @Override
        public SchemaSnapshot load() throws Exception {
            if (fail) throw new SocketTimeoutException("Connect timed out");
            return new SchemaSnapshot(id, "TestDB", tables, fks, List.of());
        }
    }

    /** .sql 파일의 줄마다 쿼리 한 건 */
    static final class LineExtractor implements SqlExtractor {
        @Override public String name() { return "lines"; }
        @Override public boolean supports(String rel) { return rel.endsWith(".sql"); }

        @Override
        public List<ExtractedSql> extract(String rel, String content) {
            List<ExtractedSql> out = new ArrayList<>();
            List<String> lines = content.lines().toList();
            for (int i = 0; i < lines.size(); i++) {
                if (lines.get(i).isBlank()) continue;
                out.add(new ExtractedSql(lines.get(i), rel + "#" + (i + 1), null, new SourceLocation(rel, i + 1), false));
            }
            return out;
        }
    }

    static final class CountingEmbedder implements Embedder {
        final AtomicInteger texts = new AtomicInteger();

        @Override public String modelId() { return "count-3"; }
        @Override public int dim() { return 3; }

        @Override
        public float[][] embed(List<String> t, Kind kind) {
            texts.addAndGet(t.size());
            float[][] out = new float[t.size()][];
            for (int i = 0; i < out.length; i++) out[i] = new float[]{1, t.get(i).length(), 2};
            return out;
        }
    }

    static DbTable emp() {
        return new DbTable("HR", "HR_EMP", "TABLE", "사원", List.of(
                new DbColumn("EMP_NO", "VARCHAR", 10, false, true, 1, "사번"),
                new DbColumn("EMP_NM", "VARCHAR", 50, true, false, 2, "성명"),
                new DbColumn("DEPT_CD", "VARCHAR", 5, true, false, 3, "부서코드"),
                new DbColumn("SAL", "NUMBER", null, true, false, 4, null)));
    }

    static DbTable dept() {
        return new DbTable("HR", "HR_DEPT", "TABLE", "부서", List.of(
                new DbColumn("DEPT_CD", "VARCHAR", 5, false, true, 1, "부서코드"),
                new DbColumn("DEPT_NM", "VARCHAR", 50, true, false, 2, "부서명")));
    }

    static DbTable payMonth() {
        return new DbTable("PAY", "PAY_MONTH", "TABLE", "월급여", List.of(
                new DbColumn("EMP_NO", "VARCHAR", 10, false, true, 1, "사번"),
                new DbColumn("NET_AMT", "NUMBER", 15, true, false, 2, "실지급액")));
    }

    Indexer.Report index(GraphStore store, List<SchemaSource> sources, String... queries) throws Exception {
        Path project = dir.resolve("project");
        Files.createDirectories(project);
        Files.writeString(project.resolve("q.sql"), String.join("\n", queries));
        return new Indexer(null).run(sources, List.of(new LineExtractor()),
                new Indexer.Options(project, List.of(), List.of(), 1_000_000), store);
    }

    @Test
    void failedReindexKeepsPreviousMetadataAndUpdatesQueries() throws Exception {
        FlakySource hr = new FlakySource("hr", List.of(emp(), dept()));
        hr.fks = List.of(new ForeignKey("HR_EMP", "DEPT_CD", "HR_DEPT", "DEPT_CD"));
        try (GraphStore st = GraphStore.open(dir.resolve("g.db"))) {
            Indexer.Report first = index(st, List.of(hr), "SELECT EMP_NM FROM HR_EMP WHERE EMP_NO = ?");
            assertEquals(2, first.tables());
            assertEquals(6, first.columns());
            assertTrue(first.staleDatabases().isEmpty());
            String loadedAt = st.meta().get(Indexer.META_DB_LOADED_AT + "hr");
            assertNotNull(loadedAt);
            List<GraphStore.ColumnInfo> colsBefore = st.columns(st.findTables("HR_EMP", null).getFirst().id());
            EmbeddingIndexer.Result emb1 = new EmbeddingIndexer().run(st, new CountingEmbedder(), 4);
            assertTrue(emb1.docs() > 0);

            // 사내망 끊김: 이번에는 메타데이터를 못 읽는다. 소스에는 쿼리가 하나 늘었다
            hr.fail = true;
            Indexer.Report second = index(st, List.of(hr),
                    "SELECT EMP_NM FROM HR_EMP WHERE EMP_NO = ?",
                    "SELECT DEPT_NM FROM HR_EMP E JOIN HR_DEPT D ON E.DEPT_CD = D.DEPT_CD");

            assertEquals(2, second.tables(), "테이블은 이전 그대로");
            assertEquals(6, second.columns());
            assertEquals(1, second.databases());
            assertEquals(List.of("hr (테이블 2개, 마지막 성공 " + loadedAt + ")"), second.staleDatabases());
            assertTrue(second.warnings().stream().anyMatch(w -> w.startsWith("[hr] 메타데이터 수집 실패: Connect timed out")
                    && w.contains("이전 메타데이터 유지 (마지막 성공 " + loadedAt + ")")), second.warnings().toString());
            assertEquals(2, second.statements(), "소스 쿼리는 새로 반영");

            Map<String, Integer> c = st.counts();
            assertEquals(2, c.get("tables"));
            assertEquals(6, c.get("columns"));
            assertEquals(2, c.get("statements"));
            assertEquals(0, c.get("unknownTables"));
            GraphStore.TableHit empHit = st.findTables("HR_EMP", null).getFirst();
            assertEquals("hr", empHit.db());
            assertEquals("HR", empHit.schema());
            assertEquals("사원", empHit.comment());
            assertEquals(colsBefore, st.columns(empHit.id()), "컬럼의 타입·크기·NULL·PK·코멘트까지 그대로");
            assertEquals(List.of(new GraphStore.FkInfo("DEPT_CD", "HR_DEPT", "DEPT_CD")), st.foreignKeys(empHit.id()));
            assertEquals(List.of(Map.entry("hr", "TestDB")), st.databases());
            assertEquals("hr (테이블 2개, 마지막 성공 " + loadedAt + ")", st.meta().get(Indexer.META_STALE_DATABASES));
            // 이전 메타데이터로 테이블 없는 컬럼도 해석된다 (WHERE EMP_NO = ? → HR_EMP)
            assertFalse(st.stmtsForColumn("HR_EMP", "EMP_NO", 10).isEmpty());

            // 테이블이 그대로이므로 임베딩은 전부 캐시 재사용
            CountingEmbedder e2 = new CountingEmbedder();
            EmbeddingIndexer.Result emb2 = new EmbeddingIndexer().run(st, e2, 4);
            assertEquals(0, e2.texts.get(), "새로 임베딩할 문서가 없어야 한다");
            assertEquals(emb1.docs(), emb2.docs());
            assertEquals(emb1.docs(), emb2.reused());

            // 또 실패해도 마지막 성공 시각은 처음 성공한 때
            Indexer.Report third = index(st, List.of(hr), "SELECT 1 FROM HR_EMP");
            assertEquals(2, third.tables());
            assertEquals(List.of("hr (테이블 2개, 마지막 성공 " + loadedAt + ")"), third.staleDatabases());

            // DB 가 돌아오면 새로 읽고 표시가 사라진다
            hr.fail = false;
            hr.tables = List.of(emp(), dept(), payMonth());
            Indexer.Report back = index(st, List.of(hr), "SELECT 1 FROM HR_EMP");
            assertEquals(3, back.tables());
            assertTrue(back.staleDatabases().isEmpty());
            assertEquals("", st.meta().get(Indexer.META_STALE_DATABASES));
            assertNotEquals(loadedAt, st.meta().get(Indexer.META_DB_LOADED_AT + "hr"), "성공 시각이 갱신되어야 한다");
        }
    }

    @Test
    void onlyTheFailedDatabaseKeepsPreviousMetadata() throws Exception {
        FlakySource hr = new FlakySource("hr", List.of(emp()));
        FlakySource pay = new FlakySource("pay", List.of(payMonth()));
        try (GraphStore st = GraphStore.open(dir.resolve("g.db"))) {
            index(st, List.of(hr, pay), "SELECT 1 FROM HR_EMP");
            String payLoadedAt = st.meta().get(Indexer.META_DB_LOADED_AT + "pay");

            // hr 은 읽히고(테이블이 늘었다) pay 만 실패
            hr.tables = List.of(emp(), dept());
            pay.fail = true;
            Indexer.Report r = index(st, List.of(hr, pay), "SELECT 1 FROM HR_EMP", "SELECT 1 FROM PAY_MONTH");

            assertEquals(2, r.databases());
            assertEquals(3, r.tables(), "hr 의 새 테이블 2개 + pay 의 이전 테이블 1개");
            assertEquals(List.of("pay (테이블 1개, 마지막 성공 " + payLoadedAt + ")"), r.staleDatabases());
            assertEquals("hr", st.findTables("HR_DEPT", null).getFirst().db(), "hr 은 새로 읽은 것");
            GraphStore.TableHit p = st.findTables("PAY_MONTH", null).getFirst();
            assertEquals("pay", p.db());
            assertEquals(2, p.columnCount());
            assertEquals(1, p.stmtCount());
            assertTrue(r.warnings().stream().anyMatch(w -> w.startsWith("[pay] ") && w.contains("이전 메타데이터 유지")));
            assertFalse(r.warnings().stream().anyMatch(w -> w.startsWith("[hr] ")));
        }
    }

    @Test
    void firstIndexingFailureProceedsWithoutTables() throws Exception {
        FlakySource hr = new FlakySource("hr", List.of(emp()));
        hr.fail = true;
        try (GraphStore st = GraphStore.open(dir.resolve("g.db"))) {
            Indexer.Report r = index(st, List.of(hr), "SELECT 1 FROM HR_EMP");

            assertEquals(0, r.databases());
            assertEquals(0, r.tables());
            assertEquals(1, r.statements());
            assertTrue(r.staleDatabases().isEmpty());
            assertEquals(List.of("[hr] 메타데이터 수집 실패: Connect timed out"), r.warnings());
            assertEquals(0, st.counts().get("tables"));
            assertEquals("", st.meta().get(Indexer.META_STALE_DATABASES));

            // 실패한 채로 다시 해도 유지할 것이 없으니 그대로 0
            Indexer.Report again = index(st, List.of(hr), "SELECT 1 FROM HR_EMP");
            assertEquals(0, again.tables());
            assertTrue(again.staleDatabases().isEmpty());
        }
    }

    @Test
    void indexFromBeforeThisFeatureUsesIndexedAtAsLastSuccess() throws Exception {
        try (GraphStore st = GraphStore.open(dir.resolve("g.db"))) {
            // 이전 버전이 쓴 인덱스: DB 별 성공 시각이 없다
            st.replaceAll(List.of(new SchemaSnapshot("hr", "TestDB", List.of(emp()), List.of(), List.of())),
                    List.of(), Map.of("indexed_at", "2026-11-26T09:00:00Z"));
            FlakySource hr = new FlakySource("hr", List.of(emp()));
            hr.fail = true;
            Indexer.Report r = index(st, List.of(hr), "SELECT 1 FROM HR_EMP");

            assertEquals(1, r.tables());
            assertEquals(List.of("hr (테이블 1개, 마지막 성공 2026-11-26T09:00:00Z)"), r.staleDatabases());
            assertEquals("2026-11-26T09:00:00Z", st.meta().get(Indexer.META_DB_LOADED_AT + "hr"));
        }
    }
}
