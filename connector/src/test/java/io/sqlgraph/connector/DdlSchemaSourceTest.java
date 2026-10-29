package io.sqlgraph.connector;

import io.sqlgraph.core.model.DbColumn;
import io.sqlgraph.core.model.DbTable;
import io.sqlgraph.core.model.SchemaSnapshot;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DdlSchemaSourceTest {

    private static final Path SAMPLES = Path.of("../samples/hr-project/ddl");

    private static Map<String, DbTable> load(String file, String dialect) throws Exception {
        SchemaSnapshot s = new DdlSchemaSource("t", List.of(SAMPLES.resolve(file)), dialect).load();
        return s.tables().stream().collect(Collectors.toMap(DbTable::name, Function.identity()));
    }

    private static DbColumn col(DbTable t, String name) {
        return t.columns().stream().filter(c -> c.name().equals(name)).findFirst().orElseThrow();
    }

    @Test
    void mariadbDump() throws Exception {
        Map<String, DbTable> t = load("mariadb_hr.sql", "mariadb");
        assertEquals(4, t.size());
        DbTable emp = t.get("HR_EMP");
        assertEquals("사원 기본", emp.comment());
        assertEquals(8, emp.columns().size());
        assertTrue(col(emp, "EMP_NO").primaryKey());
        assertFalse(col(emp, "EMP_NO").nullable());
        assertEquals("사번", col(emp, "EMP_NO").comment());
        assertEquals(10, col(emp, "EMP_NO").size());
        DbTable code = t.get("CM_CODE");
        assertTrue(col(code, "GRP_CD").primaryKey() && col(code, "CD").primaryKey());
    }

    @Test
    void oracleExportWithCommentsAndAlter() throws Exception {
        SchemaSnapshot s = new DdlSchemaSource("pay", List.of(SAMPLES.resolve("oracle_pay.sql")), "oracle").load();
        Map<String, DbTable> t = s.tables().stream().collect(Collectors.toMap(DbTable::name, Function.identity()));
        DbTable m = t.get("PAY_MONTH");
        assertNotNull(m);
        assertEquals("PAY", m.schema());
        assertEquals("월급여", m.comment());
        assertEquals("실지급액", col(m, "NET_PAY_AMT").comment());
        assertTrue(col(m, "PAY_YM").primaryKey(), "ALTER TABLE ... PRIMARY KEY 반영");
        DbTable item = t.get("PAY_ITEM");
        assertEquals("급여항목별 금액", item.comment());
        assertTrue(col(item, "ITEM_CD").primaryKey(), "인라인 CONSTRAINT PK 반영");
        assertEquals("VIEW", t.get("V_PAY_SUMMARY").kind());
        assertTrue(s.foreignKeys().stream().anyMatch(f -> f.table().equals("PAY_ITEM") && f.refTable().equals("PAY_MONTH")));
    }
}
