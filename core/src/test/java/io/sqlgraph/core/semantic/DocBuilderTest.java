package io.sqlgraph.core.semantic;

import io.sqlgraph.core.model.DbColumn;
import io.sqlgraph.core.model.DbTable;
import io.sqlgraph.core.store.GraphStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import static io.sqlgraph.core.semantic.SemanticFixture.col;
import static io.sqlgraph.core.semantic.SemanticFixture.stmt;
import static io.sqlgraph.core.semantic.SemanticFixture.table;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DocBuilderTest {

    @TempDir
    Path dir;

    @Test
    void splitsNames() {
        assertEquals("APPNT DT", DocBuilder.splitName("APPNT_DT"));
        assertEquals("emp No", DocBuilder.splitName("empNo"));
        assertEquals("EMP", DocBuilder.splitName("EMP"));
    }

    @Test
    void buildsTableAndColumnDocs() throws Exception {
        try (GraphStore store = GraphStore.open(dir.resolve("graph.db"))) {
            SemanticFixture.load(store, List.of(
                    // ordinal 이 목록 순서와 달라도 ordinal 순
                    table("HR_EMP", "사원",
                            col("EMP_NM", 2, "성명"), col("EMP_NO", 1, "사번"),
                            col("ETC", 3, null), col("DEPT_CD", 4, "부서코드")),
                    table("TX_DED", null, col("A", 1, null)),
                    table("CM_X", "", col("CD", 1, "코드")),
                    table("NO_COMMENT", null, col("A", 1, null))),
                    List.of(stmt("TaxMapper.selectDed", "TX_DED"),
                            stmt("TaxMapper.selectDed", "TX_DED"),
                            stmt("TaxMapper.insertDed", "TX_DED"),
                            stmt("A.b", "TX_DED", "HR_EMP"),
                            stmt("Only.stmtTbl", "MISSING_T")));

            List<DocBuilder.Doc> docs = DocBuilder.build(store);
            List<String> texts = docs.stream().map(DocBuilder.Doc::text).collect(Collectors.toList());
            assertEquals(List.of(
                    "사원. 테이블 HR_EMP. 컬럼: 사번, 성명, 부서코드",
                    "사원 > 사번 (EMP NO)",
                    "사원 > 성명 (EMP NM)",
                    "사원 > 부서코드 (DEPT CD)",
                    "테이블 TX_DED. 사용 쿼리: TaxMapper.selectDed, A.b, TaxMapper.insertDed",
                    "테이블 CM_X. 컬럼: 코드",
                    "CM_X > 코드 (CD)",
                    "테이블 NO_COMMENT."), texts);

            DocBuilder.Doc t = docs.get(0);
            assertEquals("t", t.kind());
            assertNull(t.colId());
            DocBuilder.Doc c = docs.get(1);
            assertEquals("c", c.kind());
            assertEquals(t.tblId(), c.tblId());
            assertTrue(c.colId() != null);
            assertTrue(texts.stream().noneMatch(s -> s.contains("MISSING_T")), "메타데이터에 없는 테이블 문서는 없음");
        }
    }

    @Test
    void limitsColumnsAndOwners() throws Exception {
        List<DbColumn> cols = new ArrayList<>();
        for (int i = 1; i <= 35; i++) cols.add(col("C" + i, i, "컬" + i));
        List<GraphStore.IndexedStmt> stmts = new ArrayList<>();
        for (int i = 0; i < 12; i++) stmts.add(stmt(String.format("M.q%02d", i), "BIG2"));
        try (GraphStore store = GraphStore.open(dir.resolve("graph.db"))) {
            SemanticFixture.load(store, List.of(
                    new DbTable("HR", "BIG", "TABLE", "큰", cols),
                    table("BIG2", null)), stmts);
            List<DocBuilder.Doc> docs = DocBuilder.build(store);
            String big = docs.get(0).text();
            assertTrue(big.endsWith(", 컬30"), big);
            assertEquals(30, big.substring(big.indexOf("컬럼: ") + 4).split(", ").length);
            assertEquals(36 + 1, docs.size(), "테이블 2 + 컬럼 35");
            String big2 = docs.get(36).text();
            assertEquals("테이블 BIG2. 사용 쿼리: M.q00, M.q01, M.q02, M.q03, M.q04, M.q05, M.q06, M.q07, M.q08, M.q09",
                    big2);
        }
    }
}
