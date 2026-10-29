package io.sqlgraph.core;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqlScriptsTest {

    @Test
    void splitsOnSemicolonAndSlashIgnoringStringsAndComments() {
        String text = """
                -- comment; not a split
                SELECT 'a;b' FROM DUAL;
                /* block ; comment */
                UPDATE T SET A = 'it''s'
                /
                INSERT INTO T VALUES (1)""";
        List<SqlScripts.Chunk> c = SqlScripts.split(text);
        assertEquals(3, c.size());
        assertTrue(c.get(0).sql().startsWith("SELECT 'a;b'"));
        assertEquals(2, c.get(0).line());
        assertTrue(c.get(1).sql().contains("'it''s'"));
        assertEquals(4, c.get(1).line());
        assertEquals(6, c.get(2).line());
    }
}
