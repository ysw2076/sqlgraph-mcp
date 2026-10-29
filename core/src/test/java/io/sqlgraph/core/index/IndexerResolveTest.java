package io.sqlgraph.core.index;

import io.sqlgraph.core.analyze.SqlAnalyzer;
import io.sqlgraph.core.model.AnalyzedSql;
import io.sqlgraph.core.model.JoinRef;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IndexerResolveTest {

    private static final Map<String, Set<String>> COLS = Map.of(
            "HR_EMP", Set.of("EMP_NO", "DEPT_CD", "POS_CD", "RETIRE_YN"),
            "CM_CODE", Set.of("GRP_CD", "CD", "CD_NM"),
            "HR_DEPT", Set.of("DEPT_CD", "DEPT_NM"));
    private static final Map<String, Set<String>> PK = Map.of(
            "HR_EMP", Set.of("EMP_NO"), "CM_CODE", Set.of("GRP_CD", "CD"), "HR_DEPT", Set.of("DEPT_CD"));

    private final SqlAnalyzer an = new SqlAnalyzer();

    @Test
    void keepsOnlyPkLiteralsAsCommonCodeCondition() {
        AnalyzedSql a = Indexer.resolve(an.analyze("""
                SELECT 1 FROM HR_EMP e, CM_CODE c
                WHERE c.GRP_CD = 'POS' AND c.CD = e.POS_CD AND e.RETIRE_YN = 'N'"""), COLS, PK);
        assertEquals(1, a.joins().size());
        JoinRef j = a.joins().get(0);
        var codeLits = j.leftTable().equals("CM_CODE") ? j.leftLiterals() : j.rightLiterals();
        var empLits = j.leftTable().equals("HR_EMP") ? j.leftLiterals() : j.rightLiterals();
        assertEquals(java.util.List.of("GRP_CD='POS'"), codeLits, "PK 일부인 상수 조건만");
        assertTrue(empLits.isEmpty(), "RETIRE_YN 은 PK 가 아니므로 관계 조건이 아님");
    }

    @Test
    void dropsSubqueryAliasesAndResolvesUnqualified() {
        AnalyzedSql merge = Indexer.resolve(an.analyze("""
                MERGE INTO HR_EMP T USING (SELECT ? AS EMP_NO FROM DUAL) S ON (T.EMP_NO = S.EMP_NO)
                WHEN MATCHED THEN UPDATE SET T.DEPT_CD = ?"""), COLS, PK);
        assertTrue(merge.joins().isEmpty(), "S 는 서브쿼리 별칭이라 테이블 관계가 아님");

        AnalyzedSql un = Indexer.resolve(an.analyze(
                "SELECT 1 FROM HR_EMP e JOIN HR_DEPT d ON DEPT_NM = e.EMP_NO"), COLS, PK);
        assertEquals(1, un.joins().size());
        assertTrue(un.joins().get(0).leftTable() != null && un.joins().get(0).rightTable() != null);
    }
}
