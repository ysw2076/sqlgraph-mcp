package io.sqlgraph.core.analyze;

import io.sqlgraph.core.model.Access;
import io.sqlgraph.core.model.AnalyzedSql;
import io.sqlgraph.core.model.SqlKind;
import io.sqlgraph.core.model.TableRef;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 파싱 성공 경로에서 가상 테이블 DUAL 이 tables() 에서 빠지는지 확인한다. */
class DualExclusionTest {

    private final SqlAnalyzer an = new SqlAnalyzer();

    @Test
    void selectFromDualHasNoTables() {
        AnalyzedSql a = an.analyze("SELECT 1 FROM DUAL");
        assertTrue(a.parsed(), "파싱 성공 경로여야 한다");
        assertEquals(List.of(), a.tables());
    }

    @Test
    void lowercaseSchemaQualifiedDualHasNoTables() {
        AnalyzedSql a = an.analyze("SELECT SYSDATE FROM sys.dual");
        assertTrue(a.parsed(), "파싱 성공 경로여야 한다");
        assertEquals(List.of(), a.tables());
    }

    @Test
    void mergeUsingDualKeepsOnlyTargetAsWrite() {
        AnalyzedSql a = an.analyze("""
                MERGE INTO HR_EMP T
                USING (SELECT ? AS EMP_NO FROM DUAL) S
                ON (T.EMP_NO = S.EMP_NO)
                WHEN MATCHED THEN UPDATE SET T.EMP_NM = ?""");
        assertTrue(a.parsed(), "파싱 성공 경로여야 한다");
        assertEquals(SqlKind.MERGE, a.kind());
        assertEquals(List.of(new TableRef(null, "HR_EMP", Access.WRITE)), a.tables());
    }

    @Test
    void joinWithoutDualKeepsBothTables() {
        AnalyzedSql a = an.analyze("""
                SELECT e.EMP_NO, d.DEPT_NM FROM HR_EMP e
                JOIN HR_DEPT d ON d.DEPT_CD = e.DEPT_CD""");
        assertTrue(a.parsed());
        assertEquals(2, a.tables().size());
        Set<String> names = a.tables().stream().map(TableRef::name).collect(Collectors.toSet());
        assertEquals(Set.of("HR_EMP", "HR_DEPT"), names);
        assertTrue(a.tables().stream().allMatch(t -> t.access() == Access.READ));
    }
}
