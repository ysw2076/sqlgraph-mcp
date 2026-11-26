package io.sqlgraph.core.semantic;

import io.sqlgraph.core.store.GraphStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EmbeddingSchemaTest {

    @TempDir
    Path dir;

    private static Set<String> tables(Path file) throws Exception {
        Set<String> r = new HashSet<>();
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath());
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT name FROM sqlite_master WHERE type IN ('table','index')")) {
            while (rs.next()) r.add(rs.getString(1));
        }
        return r;
    }

    @Test
    void newFileHasEmbeddingTablesAndVersion3() throws Exception {
        Path f = dir.resolve("graph.db");
        try (GraphStore store = GraphStore.open(f)) {
            assertEquals("3", store.meta().get("schema_version"));
            assertEquals(0, store.embCount());
            assertEquals(0, store.embCacheCount());
        }
        Set<String> t = tables(f);
        assertTrue(t.contains("emb"), t.toString());
        assertTrue(t.contains("emb_cache"), t.toString());
        assertTrue(t.contains("ix_emb_tbl"), t.toString());
    }

    @Test
    void opensV2File() throws Exception {
        Path f = dir.resolve("old.db");
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + f.toAbsolutePath());
             Statement s = c.createStatement()) {
            s.execute("CREATE TABLE meta(k TEXT PRIMARY KEY, v TEXT)");
            s.execute("INSERT INTO meta VALUES('schema_version', '2')");
            s.execute("CREATE TABLE tbl(id INTEGER PRIMARY KEY, db_id TEXT, schema_name TEXT, name TEXT, "
                    + "name_key TEXT, kind TEXT, comment TEXT)");
            s.execute("INSERT INTO tbl VALUES(1, 'main', 'HR', 'HR_EMP', 'HR_EMP', 'TABLE', '사원')");
        }
        try (GraphStore store = GraphStore.open(f)) {
            assertEquals("3", store.meta().get("schema_version"));
            assertEquals(1, store.docTables().size());
            assertEquals(0, store.embCount());
        }
    }

    @Test
    void vectorIsLittleEndianFloat32() {
        float[] v = {1.0f, -2.5f};
        byte[] b = GraphStore.encodeVector(v);
        assertEquals(8, b.length);
        // 1.0f = 0x3F800000 → 리틀엔디언 00 00 80 3F
        assertEquals(0x00, b[0] & 0xff);
        assertEquals(0x80, b[2] & 0xff);
        assertEquals(0x3F, b[3] & 0xff);
        assertArrayEquals(v, GraphStore.decodeVector(b));
    }
}
