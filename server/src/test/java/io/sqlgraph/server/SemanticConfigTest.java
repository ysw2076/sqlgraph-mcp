package io.sqlgraph.server;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SemanticConfigTest {

    @TempDir
    Path dir;

    private Config load(String yml) throws Exception {
        Path f = dir.resolve("sqlgraph.yml");
        Files.writeString(f, yml, StandardCharsets.UTF_8);
        return Config.load(f, dir.resolve("idx/graph.db"));
    }

    @Test
    void parsesSemanticBlockWithRelativePaths() throws Exception {
        Config c = load("""
                project: .
                semantic:
                  enabled: true
                  provider: onnx
                  modelPath: models/e5
                  runtimeJar: libs/ort.jar
                  threads: 4
                """);
        Config.Semantic s = c.semantic();
        assertTrue(s.enabled());
        Path base = dir.toAbsolutePath().normalize();
        assertEquals(base.resolve("models/e5").normalize(), s.modelPath());
        assertEquals(base.resolve("libs/ort.jar").normalize(), s.runtimeJar());
        assertEquals(4, s.threads());
    }

    @Test
    void defaults() throws Exception {
        Config.Semantic s = load("semantic:\n  modelPath: m\n").semantic();
        assertFalse(s.enabled(), "enabled 기본 false");
        assertNull(s.runtimeJar());
        assertEquals(0, s.threads());

        Config.Semantic on = load("semantic:\n  enabled: true\n").semantic();
        assertTrue(on.enabled());
        assertNull(on.modelPath());
        assertNull(on.runtimeJar());
        assertEquals(0, on.threads());
    }

    @Test
    void noBlockIsOff() throws Exception {
        assertEquals(Config.Semantic.OFF, load("project: .\n").semantic());
        assertEquals(Config.Semantic.OFF, Config.ofProject(dir, dir.resolve("g.db"), null).semantic());
    }

    @Test
    void unsupportedProviderFails() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> load("semantic:\n  enabled: true\n  provider: http\n"));
        assertTrue(e.getMessage().contains("http"), e.getMessage());
        assertTrue(e.getMessage().contains("onnx"), e.getMessage());
    }

    @Test
    void envOverride() throws Exception {
        Config c = load("semantic:\n  enabled: false\n  modelPath: m\n  threads: 2\n");
        for (String v : new String[]{"on", "TRUE", "1", " On "}) {
            Config on = Config.applySemanticEnv(c, v);
            assertTrue(on.semantic().enabled(), v);
            assertEquals(c.semantic().modelPath(), on.semantic().modelPath());
            assertEquals(2, on.semantic().threads());
        }
        Config enabled = Config.applySemanticEnv(c, "on");
        for (String v : new String[]{"off", "False", "0"}) {
            assertFalse(Config.applySemanticEnv(enabled, v).semantic().enabled(), v);
        }
        assertSame(c, Config.applySemanticEnv(c, null));
        assertSame(c, Config.applySemanticEnv(c, ""));
        assertSame(c, Config.applySemanticEnv(c, "maybe"));
        assertSame(enabled, Config.applySemanticEnv(enabled, "yes?"));
    }

    @Test
    void derivedConfigsKeepSemantic() throws Exception {
        Config c = load("semantic:\n  enabled: true\n  threads: 3\n");
        assertEquals(c.semantic(), c.withProfile("dev").semantic());
        assertEquals("dev", c.withProfile("dev").profile());
        assertEquals(c.semantic(), c.withoutDatabases().semantic());
        Config.Semantic other = new Config.Semantic(false, null, null, 1);
        assertEquals(other, c.withSemantic(other).semantic());
    }

    @Test
    void legacyConstructorIsOff() {
        Config c = new Config(null, dir, dir, dir.resolve("g.db"), java.util.List.of(), Config.DEFAULT_INCLUDE,
                java.util.List.of(), 1024, true, null);
        assertEquals(Config.Semantic.OFF, c.semantic());
    }
}
