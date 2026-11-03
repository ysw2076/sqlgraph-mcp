package io.sqlgraph.core;

import io.sqlgraph.core.analyze.SqlAnalyzer;
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

/** JSqlParser 가 1차로 못 읽는 MySQL/MariaDB 표현을 2차 보정으로 읽는지 (여러 실무 Mapper 에서 흔한 패턴) */
class DialectRelaxTest {

    private final SqlAnalyzer an = new SqlAnalyzer();

    private AnalyzedSql ok(String sql) {
        AnalyzedSql a = an.analyze(sql);
        assertTrue(a.parsed(), sql + " → " + a.error());
        return a;
    }

    private static Set<String> tables(AnalyzedSql a) {
        return a.tables().stream().map(TableRef::name).collect(Collectors.toSet());
    }

    private static Set<String> cols(AnalyzedSql a) {
        return a.columns().stream().map(ColumnRef::column).collect(Collectors.toSet());
    }

    @Test
    void mysqlLogicalOr() {
        AnalyzedSql a = ok("UPDATE APPR_LINE SET ST = ? WHERE DOC_NO = ? AND (SEQ_NO = '1' || STATUS_CD = '3')");
        assertTrue(cols(a).containsAll(Set.of("SEQ_NO", "STATUS_CD")));
    }

    @Test
    void columnStartingWithDigit() {
        AnalyzedSql a = ok("SELECT C.ID, C.4QTR_AMT AS Q4 FROM CONTRACT C");
        assertTrue(cols(a).contains("4QTR_AMT"), cols(a).toString());
    }

    @Test
    void hugeLimitAndHashComment() {
        String raw = """
                SELECT A.TICKET_ID # 티켓 ID
                     , A.NM      # 이름
                  FROM (SELECT TICKET_ID, NM FROM TICKET ORDER BY REG_DT DESC LIMIT 18446744073709551615) A
                 WHERE A.NM = #{nm}
                """;
        String collapsed = SqlComments.lineToBlock(raw).replaceAll("\\s+", " ");
        assertTrue(collapsed.contains("#{nm}"), "MyBatis 바인드는 주석이 아님");
        AnalyzedSql a = ok(collapsed);
        assertEquals(Set.of("TICKET"), tables(a));
    }

    @Test
    void ibatisBindIsNotAComment() {
        String raw = "SELECT A FROM T WHERE B = #b# AND C = #c:VARCHAR#";
        assertEquals(raw, SqlComments.lineToBlock(raw));
    }

    @Test
    void intervalTimestampdiffDivAndSeparator() {
        ok("SELECT DATE_FORMAT(DATE_ADD(STR_TO_DATE(P.HIRE_DATE, '%Y%m%d'), INTERVAL CAST(B.COL1 AS SIGNED) DAY), '%Y%m%d') AS D "
                + "FROM EMP P JOIN BASE B ON B.K = P.K");
        ok("SELECT CONCAT(TIMESTAMPDIFF(MONTH, STR_TO_DATE(A.ENT_DT, '%Y%m%d'), NOW()) DIV 12, '년') AS Y FROM EMP A");
        AnalyzedSql g = ok("SELECT E.ID, (SELECT GROUP_CONCAT(L.NM ORDER BY L.SEQ SEPARATOR ', ') FROM LIC L WHERE L.ID = E.ID) AS LICS FROM EMP E");
        assertEquals(Set.of("EMP", "LIC"), tables(g));
    }

    @Test
    void deleteAliasStarAndReservedWords() {
        AnalyzedSql d = ok("DELETE OI.* FROM ORDER_ITEM OI, (SELECT ORDER_ID FROM ORDER_TMP) OT WHERE OI.ORDER_ID = OT.ORDER_ID");
        assertTrue(tables(d).containsAll(Set.of("ORDER_ITEM", "ORDER_TMP")));
        ok("SELECT DATE_FORMAT(WK.WORK_DT, '%Y-%m-%d') AS START, WK.TITLE FROM WORK_CAL WK");
        ok("SELECT ID, NAME FROM SAMPLE");
    }

    @Test
    void sessionStatementsHaveNoTables() {
        AnalyzedSql a = an.analyze("SET SESSION group_concat_max_len = 4096");
        assertTrue(a.parsed());
        assertEquals(SqlKind.OTHER, a.kind());
        assertTrue(a.tables().isEmpty());
        assertFalse(an.analyze("UPDATE T SET A = 1 WHERE B = 2").tables().isEmpty(), "UPDATE ... SET 은 해당 없음");
    }

    @Test
    void danglingDynamicFragments() {
        ok("UPDATE IF_DATA T JOIN ${tbl} P ON P.K = T.K SET ${cols} , T.ROW_ST = '20' WHERE T.CO = ?"
                .replace("${tbl}", "__DYN__").replace("${cols}", "__DYN__"));
        ok("SELECT W.CD FROM WORK_CD W WHERE W.USE_YN = 'Y' __DYN__ ORDER BY W.ORD");
    }

    @Test
    void windowFrameWithoutBoundExpression() {
        AnalyzedSql a = ok("SELECT ID, SUM(AMT) OVER (PARTITION BY GRP ORDER BY DT ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW) S FROM LEDGER");
        assertTrue(cols(a).containsAll(Set.of("AMT", "GRP", "DT")), cols(a).toString());
    }

    @Test
    void backslashEscapedString() {
        ok("UPDATE FORM_DEF SET BODY = '{\\\"a\\\":\\\"b\\'c\\\"}' WHERE FORM_ID = 'F1'");
    }

    @Test
    void relaxKeepsCastTypesAndTrimFunctions() {
        ok("SELECT CONCAT(TRIM(TRAILING '.' FROM TRIM(TRAILING '0' FROM CAST(A.D AS CHAR))), 'x') AS V, LAST_DAY(CONCAT(A.YM, '01')) LD "
                + "FROM WORK_D A WHERE (A.X = 1 || A.Y = 2) AND LAST_DAY(CONCAT(A.YM, '01')) BETWEEN A.F AND A.T");
        AnalyzedSql a = ok("SELECT IFNULL(SUM(CAST(W.DAYS AS DECIMAL(10,2))), 0) AS START FROM WORK_DESC W WHERE (W.A = 1 || W.B = 2)");
        assertTrue(cols(a).contains("DAYS"));
    }

    @Test
    void unicodeSpacesAreWhitespace() {
        ok("SELECT\u00A0A,\u3000B FROM T\u00A0WHERE C = 1");
    }
}
