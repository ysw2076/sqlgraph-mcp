package io.sqlgraph.core.analyze;

import io.sqlgraph.core.model.Access;
import io.sqlgraph.core.model.AnalyzedSql;
import io.sqlgraph.core.model.TableRef;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** DB2 가상 테이블 SYSIBM.SYSDUMMY1 이 tables() 에서 빠지는지 확인한다. */
class SysDummy1ExclusionTest {

    private final SqlAnalyzer an = new SqlAnalyzer();

    @Test
    void schemaQualifiedIsExcluded() {
        AnalyzedSql a = an.analyze("SELECT 1 FROM SYSIBM.SYSDUMMY1");
        assertTrue(a.parsed());
        assertEquals(List.of(), a.tables());
    }

    @Test
    void lowercaseWithoutSchemaIsExcluded() {
        AnalyzedSql a = an.analyze("select current timestamp from sysdummy1");
        assertEquals(List.of(), a.tables());
        a = an.analyze("select 1 from sysibm.sysdummy1");
        assertEquals(List.of(), a.tables());
    }

    @Test
    void mergeKeepsRealTable() {
        AnalyzedSql a = an.analyze("""
                MERGE INTO HR_EMP T
                USING (SELECT ? AS EMP_NO FROM SYSIBM.SYSDUMMY1) S
                ON (T.EMP_NO = S.EMP_NO)
                WHEN MATCHED THEN UPDATE SET T.EMP_NM = ?""");
        assertTrue(a.parsed());
        assertEquals(List.of(new TableRef(null, "HR_EMP", Access.WRITE)), a.tables());
    }

    @Test
    void insertSelectKeepsRealTables() {
        AnalyzedSql a = an.analyze(
                "INSERT INTO HR_EMP (EMP_NO) SELECT S.EMP_NO FROM HR_SRC S, SYSIBM.SYSDUMMY1 D");
        assertTrue(a.parsed());
        assertEquals(List.of(new TableRef(null, "HR_EMP", Access.WRITE),
                new TableRef(null, "HR_SRC", Access.READ)), a.tables());
    }

    @Test
    void otherSchemaIsKept() {
        AnalyzedSql a = an.analyze("SELECT 1 FROM MYSCHEMA.SYSDUMMY1");
        assertTrue(a.parsed());
        assertEquals(List.of(new TableRef("MYSCHEMA", "SYSDUMMY1", Access.READ)), a.tables());
    }
}
