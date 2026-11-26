package io.sqlgraph.connector.embed;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OrtRunnerTest {

    @TempDir
    Path dir;

    @Test
    void missingJarThrowsWithPath() throws Exception {
        Path jar = dir.resolve("no-such-onnxruntime.jar");
        Path model = Files.writeString(dir.resolve("model.onnx"), "x");
        IOException e = assertThrows(IOException.class, () -> OrtRunner.open(jar, model, 1));
        assertTrue(e.getMessage().contains(jar.toString()));
    }

    @Test
    void missingModelThrowsWithPath() throws Exception {
        Path jar = Files.writeString(dir.resolve("ort.jar"), "x");
        Path model = dir.resolve("missing.onnx");
        IOException e = assertThrows(IOException.class, () -> OrtRunner.open(jar, model, 1));
        assertTrue(e.getMessage().contains(model.toString()));
    }

    @Test
    void poolingAveragesMaskedTokens() {
        float[][][] hidden = {{{1, 2}, {3, 4}, {100, 100}}};
        long[][] mask = {{1, 1, 0}};
        assertArrayEquals(new float[]{2, 3}, OrtRunner.pool(hidden, mask)[0]);
    }
}
