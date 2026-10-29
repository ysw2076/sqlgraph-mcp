package io.sqlgraph.core;

import io.sqlgraph.core.analyze.SqlAnalyzer;
import io.sqlgraph.core.model.Access;
import io.sqlgraph.core.model.AnalyzedSql;
import io.sqlgraph.core.model.ColumnRef;
import io.sqlgraph.core.model.SqlKind;
import io.sqlgraph.core.model.TableRef;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqlAnalyzerTest {

    private final SqlAnalyzer an = new SqlAnalyzer();

    private static Set<String> tables(AnalyzedSql a, Access acc) {
        return a.tables().stream().filter(t -> t.access() == acc).map(TableRef::name).collect(Collectors.toSet());
    }

    private static Set<String> cols(AnalyzedSql a, Access acc) {
        return a.columns().stream().filter(c -> c.access() == acc)
                .map(c -> c.table() + "." + c.column()).collect(Collectors.toSet());
    }

    @Test
    void selectWithAliasesAndJoins() {
        AnalyzedSql a = an.analyze("""
                SELECT e.EMP_NO, d.DEPT_NM FROM HR_EMP e
                LEFT JOIN HR_DEPT d ON d.DEPT_CD = e.DEPT_CD
                WHERE e.EMP_NO = #{empNo}""");
        assertTrue(a.parsed());
        assertEquals(SqlKind.SELECT, a.kind());
        assertEquals(Set.of("HR_EMP", "HR_DEPT"), tables(a, Access.READ));
        assertTrue(cols(a, Access.READ).containsAll(Set.of("HR_EMP.EMP_NO", "HR_DEPT.DEPT_NM", "HR_DEPT.DEPT_CD", "HR_EMP.DEPT_CD")));
        assertFalse(a.selectsAll());
    }

    @Test
    void unqualifiedColumnsWithSingleTable() {
        AnalyzedSql a = an.analyze("SELECT COUNT(*) FROM HR_EMP WHERE DEPT_CD = ? AND RETIRE_YN = 'N'");
        assertTrue(cols(a, Access.READ).containsAll(Set.of("HR_EMP.DEPT_CD", "HR_EMP.RETIRE_YN")));
        assertFalse(a.selectsAll(), "COUNT(*) 는 SELECT * 가 아니다");
    }

    @Test
    void selectStarDetected() {
        assertTrue(an.analyze("SELECT * FROM HR_EMP WHERE EMP_NO = ?").selectsAll());
        assertTrue(an.analyze("SELECT e.* FROM HR_EMP e").selectsAll());
    }

    @Test
    void insertColumnsAreWrites() {
        AnalyzedSql a = an.analyze("INSERT INTO HR_EMP (EMP_NO, EMP_NM) VALUES (#{a}, #{b})");
        assertEquals(SqlKind.INSERT, a.kind());
        assertEquals(Set.of("HR_EMP"), tables(a, Access.WRITE));
        assertEquals(Set.of("HR_EMP.EMP_NO", "HR_EMP.EMP_NM"), cols(a, Access.WRITE));
    }

    @Test
    void insertSelectReadsSource() {
        AnalyzedSql a = an.analyze("INSERT INTO HR_APPNT (EMP_NO, BEF_DEPT_CD) SELECT EMP_NO, DEPT_CD FROM HR_EMP WHERE EMP_NO = ?");
        assertEquals(Set.of("HR_APPNT"), tables(a, Access.WRITE));
        assertTrue(tables(a, Access.READ).contains("HR_EMP"));
        assertTrue(cols(a, Access.WRITE).contains("HR_APPNT.BEF_DEPT_CD"));
        assertTrue(cols(a, Access.READ).contains("HR_EMP.DEPT_CD"));
    }

    @Test
    void updateWithAliasAndSubquery() {
        AnalyzedSql a = an.analyze("""
                UPDATE HR_EMP e SET e.RETIRE_YN = 'Y'
                WHERE e.EMP_NO = ? AND NOT EXISTS (SELECT 1 FROM HR_APPNT a WHERE a.EMP_NO = e.EMP_NO)""");
        assertEquals(Set.of("HR_EMP"), tables(a, Access.WRITE));
        assertTrue(tables(a, Access.READ).contains("HR_APPNT"));
        assertEquals(Set.of("HR_EMP.RETIRE_YN"), cols(a, Access.WRITE));
        assertTrue(cols(a, Access.READ).contains("HR_APPNT.EMP_NO"));
    }

    @Test
    void multiTableUpdateMarksJoinedTableAsWrite() {
        AnalyzedSql a = an.analyze("""
                UPDATE HR_EMP e JOIN HR_APPNT a ON a.EMP_NO = e.EMP_NO
                SET e.DEPT_CD = a.AFT_DEPT_CD, a.STATUS_CD = '90' WHERE a.APPNT_ID = ?""");
        assertEquals(Set.of("HR_EMP", "HR_APPNT"), tables(a, Access.WRITE));
        assertTrue(cols(a, Access.WRITE).containsAll(Set.of("HR_EMP.DEPT_CD", "HR_APPNT.STATUS_CD")));
    }

    @Test
    void oracleMerge() {
        AnalyzedSql a = an.analyze("""
                MERGE INTO PAY_MONTH T
                USING (SELECT ? AS PAY_YM, SUM(ITEM_AMT) AS AMT FROM PAY_ITEM WHERE PAY_YM = ?) S
                ON (T.PAY_YM = S.PAY_YM)
                WHEN MATCHED THEN UPDATE SET T.TOT_PAY_AMT = S.AMT
                WHEN NOT MATCHED THEN INSERT (PAY_YM, TOT_PAY_AMT) VALUES (S.PAY_YM, S.AMT)""");
        assertTrue(a.parsed(), a.error());
        assertEquals(SqlKind.MERGE, a.kind());
        assertEquals(Set.of("PAY_MONTH"), tables(a, Access.WRITE));
        assertTrue(tables(a, Access.READ).contains("PAY_ITEM"));
        assertTrue(cols(a, Access.WRITE).contains("PAY_MONTH.TOT_PAY_AMT"));
    }

    @Test
    void oracleOuterJoinAndRownum() {
        AnalyzedSql a = an.analyze("""
                SELECT M.EMP_NO, I.ITEM_AMT FROM PAY_MONTH M, PAY_ITEM I
                WHERE M.PAY_YM = I.PAY_YM(+) AND ROWNUM <= 10""");
        assertTrue(a.parsed(), a.error());
        assertEquals(Set.of("PAY_MONTH", "PAY_ITEM"), tables(a, Access.READ));
        assertTrue(a.columns().stream().noneMatch(c -> c.column().equalsIgnoreCase("ROWNUM")));
    }

    @Test
    void cteNamesAreNotTables() {
        AnalyzedSql a = an.analyze("""
                WITH T AS (SELECT EMP_NO FROM HR_EMP)
                SELECT T.EMP_NO FROM T JOIN HR_DEPT D ON 1 = 1""");
        assertEquals(Set.of("HR_EMP", "HR_DEPT"), tables(a, Access.READ));
    }

    @Test
    void dynamicTableNameIgnored() {
        AnalyzedSql a = an.analyze("SELECT A FROM ${tableName} WHERE B = #{b} ORDER BY ${col}");
        assertTrue(a.tables().isEmpty());
        assertTrue(a.columns().stream().map(ColumnRef::column).noneMatch(c -> c.contains("DYN")));
    }

    @Test
    void ibatisPlaceholders() {
        AnalyzedSql a = an.analyze("UPDATE HR_EMP SET EMAIL = #email# WHERE EMP_NO = #empNo#");
        assertTrue(a.parsed(), a.error());
        assertEquals(Set.of("HR_EMP.EMAIL"), cols(a, Access.WRITE));
    }

    @Test
    void jdbcCallEscape() {
        AnalyzedSql a = an.analyze("{ call PKG_PAY.CALC(#{a, mode=IN}) }");
        assertEquals(SqlKind.CALL, a.kind());
        assertTrue(a.parsed());
    }

    @Test
    void fallbackWhenUnparseable() {
        AnalyzedSql a = an.analyze("UPDATE HR_EMP SET X = DECODE(?, 1, 2 ### broken syntax WHERE EMP_NO IN (SELECT EMP_NO FROM HR_APPNT)");
        assertFalse(a.parsed());
        assertEquals(Set.of("HR_EMP"), tables(a, Access.WRITE));
        assertTrue(tables(a, Access.READ).contains("HR_APPNT"));
    }

    @Test
    void quotedIdentifiers() {
        AnalyzedSql a = an.analyze("SELECT `EMP_NO` FROM `HR_EMP`");
        assertEquals(Set.of("HR_EMP"), tables(a, Access.READ));
        AnalyzedSql b = an.analyze("SELECT \"PAY_YM\" FROM \"PAY\".\"PAY_MONTH\"");
        assertEquals("PAY", b.tables().get(0).schema());
        assertEquals("PAY_MONTH", b.tables().get(0).name());
    }

    private static Set<String> joinKeys(AnalyzedSql a) {
        return a.joins().stream().map(j -> {
            String l = j.leftTable() + "." + j.leftColumn();
            String r = j.rightTable() + "." + j.rightColumn();
            return l.compareTo(r) <= 0 ? l + "=" + r : r + "=" + l;
        }).collect(Collectors.toSet());
    }

    @Test
    void joinsFromOnClauses() {
        AnalyzedSql a = an.analyze("""
                SELECT e.EMP_NO FROM HR_EMP e
                LEFT JOIN HR_DEPT d ON d.DEPT_CD = e.DEPT_CD
                LEFT JOIN CM_CODE c ON c.GRP_CD = 'POS' AND c.CD = e.POS_CD
                WHERE e.RETIRE_YN = 'N' AND e.EMP_NO = ?""");
        assertEquals(Set.of("HR_DEPT.DEPT_CD=HR_EMP.DEPT_CD", "CM_CODE.CD=HR_EMP.POS_CD"), joinKeys(a));
        var code = a.joins().stream().filter(j -> j.leftTable().equals("CM_CODE") || j.rightTable().equals("CM_CODE")).findFirst().orElseThrow();
        var codeLits = code.leftTable().equals("CM_CODE") ? code.leftLiterals() : code.rightLiterals();
        assertTrue(codeLits.contains("GRP_CD='POS'"), "공통코드 상수 조건 후보");
    }

    @Test
    void joinsFromOracleWhereAndSelfJoinAndSubquery() {
        assertEquals(Set.of("PAY_ITEM.PAY_YM=PAY_MONTH.PAY_YM", "PAY_ITEM.EMP_NO=PAY_MONTH.EMP_NO"), joinKeys(an.analyze(
                "SELECT 1 FROM PAY_MONTH M, PAY_ITEM I WHERE M.PAY_YM = I.PAY_YM(+) AND M.EMP_NO = I.EMP_NO(+)")));
        assertEquals(Set.of("HR_DEPT.DEPT_CD=HR_DEPT.UPPER_DEPT_CD"), joinKeys(an.analyze(
                "SELECT 1 FROM HR_DEPT d1 JOIN HR_DEPT d2 ON d1.UPPER_DEPT_CD = d2.DEPT_CD")));
        assertEquals(Set.of("HR_APPNT.EMP_NO=HR_EMP.EMP_NO"), joinKeys(an.analyze("""
                UPDATE HR_EMP e SET e.RETIRE_YN = 'Y'
                WHERE NOT EXISTS (SELECT 1 FROM HR_APPNT a WHERE a.EMP_NO = e.EMP_NO)""")));
        assertTrue(an.analyze("SELECT 1 FROM T a WHERE a.X = a.Y").joins().isEmpty(), "같은 별칭 안 비교는 관계 아님");
    }
}
