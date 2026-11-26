package io.sqlgraph.core.semantic;

import io.sqlgraph.core.model.Access;
import io.sqlgraph.core.model.AnalyzedSql;
import io.sqlgraph.core.model.DbColumn;
import io.sqlgraph.core.model.DbTable;
import io.sqlgraph.core.model.ExtractedSql;
import io.sqlgraph.core.model.SchemaSnapshot;
import io.sqlgraph.core.model.SourceLocation;
import io.sqlgraph.core.model.SqlKind;
import io.sqlgraph.core.model.TableRef;
import io.sqlgraph.core.store.GraphStore;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/** 의미 검색 테스트용 데이터 준비 도우미 */
final class SemanticFixture {

    private SemanticFixture() {}

    static DbColumn col(String name, int ordinal, String comment) {
        return new DbColumn(name, "VARCHAR", 10, true, false, ordinal, comment);
    }

    static DbTable table(String name, String comment, DbColumn... cols) {
        return new DbTable("HR", name, "TABLE", comment, Arrays.asList(cols));
    }

    static GraphStore.IndexedStmt stmt(String owner, String... tables) {
        List<TableRef> refs = new ArrayList<>();
        for (String t : tables) refs.add(new TableRef(null, t, Access.READ));
        return new GraphStore.IndexedStmt(
                new ExtractedSql("SELECT 1", owner, SqlKind.SELECT, new SourceLocation("a.xml", 1), false),
                new AnalyzedSql(SqlKind.SELECT, true, null, refs, List.of(), false, List.of()));
    }

    static void load(GraphStore store, List<DbTable> tables, List<GraphStore.IndexedStmt> stmts) throws Exception {
        store.replaceAll(List.of(new SchemaSnapshot("main", "SQLite", tables, List.of(), List.of())),
                stmts, Map.of());
    }

    /** 기본 데이터: 테이블 3개, 코멘트 있는 컬럼 5개 → 문서 8개 */
    static List<DbTable> baseTables(String deptComment) {
        return List.of(
                table("HR_EMP", "사원",
                        col("EMP_NO", 1, "사번"), col("EMP_NM", 2, "성명"),
                        col("DEPT_CD", 3, "부서코드"), col("ETC", 4, null)),
                table("HR_DEPT", deptComment,
                        col("DEPT_CD", 1, "부서코드"), col("DEPT_NM", 2, "부서명")),
                table("TX_DED", null, col("A", 1, null), col("B", 2, " ")));
    }

    static List<GraphStore.IndexedStmt> baseStmts() {
        return List.of(
                stmt("TaxMapper.selectDed", "TX_DED"),
                stmt("TaxMapper.selectDed", "TX_DED"),
                stmt("TaxMapper.insertDed", "TX_DED"),
                stmt("A.b", "TX_DED", "HR_EMP"),
                stmt("X.unknown", "UNKNOWN_T"));
    }
}
