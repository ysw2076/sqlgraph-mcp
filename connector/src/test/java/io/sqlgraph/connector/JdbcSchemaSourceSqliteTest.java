package io.sqlgraph.connector;

import io.sqlgraph.core.model.DbColumn;
import io.sqlgraph.core.model.DbTable;
import io.sqlgraph.core.model.SchemaSnapshot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** jdbc:sqlite: URL 은 연결 뒤 setReadOnly 를 거부하므로 읽기 전용으로 여는 경로를 따로 확인한다 */
class JdbcSchemaSourceSqliteTest {

    private static JdbcSchemaSource source(String url) {
        return new JdbcSchemaSource(new JdbcSchemaSource.Settings(
                "sqlite", url, null, null, null, null, null, Map.of()));
    }

    @Test
    void loadsTablesAndColumnsFromSqliteFile(@TempDir Path dir) throws Exception {
        Path db = dir.resolve("app.db");
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db);
             Statement st = c.createStatement()) {
            st.execute("CREATE TABLE users (id INTEGER PRIMARY KEY, name VARCHAR(50) NOT NULL, email TEXT)");
        }

        SchemaSnapshot snap = source("jdbc:sqlite:" + db).load();

        assertEquals(1, snap.tables().size(), () -> "warnings=" + snap.warnings());
        DbTable t = snap.tables().get(0);
        assertEquals("users", t.name());
        assertEquals("TABLE", t.kind());
        List<String> names = t.columns().stream().map(DbColumn::name).toList();
        assertEquals(List.of("id", "name", "email"), names);
        DbColumn id = t.columns().get(0);
        assertTrue(id.primaryKey());
        DbColumn name = t.columns().get(1);
        assertFalse(name.nullable());
    }

    @Test
    void doesNotCreateMissingSqliteFile(@TempDir Path dir) {
        Path missing = dir.resolve("missing.db");

        assertThrows(Exception.class, () -> source("jdbc:sqlite:" + missing).load());
        assertFalse(Files.exists(missing), "읽기 전용으로 열었다면 없는 파일을 만들지 않아야 한다");
    }
}
