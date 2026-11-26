package io.sqlgraph.connector.embed;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModelResolverTest {

    @TempDir
    Path home;

    private static final byte[] MODEL = "model-bytes".getBytes(StandardCharsets.UTF_8);
    private static final byte[] TOK = "{\"tok\":1}".getBytes(StandardCharsets.UTF_8);
    private static final byte[] JAR = "jar-bytes!".getBytes(StandardCharsets.UTF_8);

    private static final URI U_MODEL = URI.create("https://example.invalid/model.onnx");
    private static final URI U_TOK = URI.create("https://example.invalid/tokenizer.json");
    private static final URI U_JAR = URI.create("https://example.invalid/ort.jar");

    private final List<URI> calls = new ArrayList<>();
    private final Map<URI, byte[]> content = Map.of(U_MODEL, MODEL, U_TOK, TOK, U_JAR, JAR);

    private final ModelResolver.Fetcher fetcher = uri -> {
        calls.add(uri);
        byte[] b = content.get(uri);
        if (b == null) throw new IOException("없음: " + uri);
        return new ByteArrayInputStream(b);
    };

    private static String sha(byte[] b) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));
    }

    private ModelResolver resolver(String modelSha) throws Exception {
        return new ModelResolver(home, fetcher, s -> { },
                new ModelResolver.Artifact("models/m/model_quantized.onnx", U_MODEL, MODEL.length, modelSha),
                new ModelResolver.Artifact("models/m/tokenizer.json", U_TOK, TOK.length, sha(TOK)),
                new ModelResolver.Artifact("lib/ort.jar", U_JAR, JAR.length, sha(JAR)));
    }

    @Test
    void defaultsArePinned() {
        List<ModelResolver.Artifact> d = ModelResolver.defaults();
        assertEquals(118_308_185L, d.get(0).size());
        assertEquals("models/multilingual-e5-small/model_quantized.onnx", d.get(0).relativePath());
        assertEquals("lib/onnxruntime-1.32.0.jar", d.get(2).relativePath());
    }

    @Test
    void downloadsWhenHashMatches() throws Exception {
        List<String> logs = new ArrayList<>();
        ModelResolver r = new ModelResolver(home, fetcher, logs::add,
                new ModelResolver.Artifact("models/m/model_quantized.onnx", U_MODEL, MODEL.length, sha(MODEL)),
                new ModelResolver.Artifact("models/m/tokenizer.json", U_TOK, TOK.length, sha(TOK)),
                new ModelResolver.Artifact("lib/ort.jar", U_JAR, JAR.length, sha(JAR)));

        ModelResolver.ModelFiles f = r.resolve(new ModelResolver.Options(null, null));

        assertEquals(home.resolve("models/m/model_quantized.onnx"), f.model());
        assertEquals(home.resolve("models/m/tokenizer.json"), f.tokenizer());
        assertEquals(home.resolve("lib/ort.jar"), f.runtimeJar());
        assertTrue(java.util.Arrays.equals(MODEL, Files.readAllBytes(f.model())));
        assertTrue(java.util.Arrays.equals(JAR, Files.readAllBytes(f.runtimeJar())));
        assertFalse(Files.exists(home.resolve("models/m/model_quantized.onnx.part")));
        assertFalse(Files.exists(home.resolve("lib/ort.jar.part")));
        assertEquals(3, calls.size());
        assertTrue(logs.stream().anyMatch(s -> s.contains("model_quantized.onnx")));
    }

    @Test
    void hashMismatchDeletesFiles() throws Exception {
        String wrong = "0".repeat(64);
        ModelResolver r = resolver(wrong);

        IOException e = assertThrows(IOException.class, () -> r.resolve(new ModelResolver.Options(null, null)));

        assertTrue(e.getMessage().contains(wrong));
        assertTrue(e.getMessage().contains(sha(MODEL)));
        assertFalse(Files.exists(home.resolve("models/m/model_quantized.onnx")));
        assertFalse(Files.exists(home.resolve("models/m/model_quantized.onnx.part")));
    }

    @Test
    void existingFileWithSameSizeNotFetched() throws Exception {
        Files.createDirectories(home.resolve("models/m"));
        Files.createDirectories(home.resolve("lib"));
        Files.write(home.resolve("models/m/model_quantized.onnx"), MODEL);
        Files.write(home.resolve("models/m/tokenizer.json"), TOK);
        Files.write(home.resolve("lib/ort.jar"), JAR);

        ModelResolver.ModelFiles f = resolver(sha(MODEL)).resolve(new ModelResolver.Options(null, null));

        assertEquals(0, calls.size());
        assertEquals(home.resolve("lib/ort.jar"), f.runtimeJar());
    }

    @Test
    void explicitPathsSkipFetcher() throws Exception {
        Path m = Files.createDirectories(home.resolve("mine"));
        Files.write(m.resolve("model.onnx"), MODEL);
        Files.write(m.resolve("tokenizer.json"), TOK);
        Path jar = Files.write(home.resolve("my-ort.jar"), JAR);

        ModelResolver.ModelFiles f = resolver(sha(MODEL)).resolve(new ModelResolver.Options(m, jar));

        assertEquals(0, calls.size());
        assertEquals(m.resolve("model.onnx"), f.model());
        assertEquals(m.resolve("tokenizer.json"), f.tokenizer());
        assertEquals(jar, f.runtimeJar());

        Files.write(m.resolve("model_quantized.onnx"), MODEL);
        assertEquals(m.resolve("model_quantized.onnx"),
                resolver(sha(MODEL)).resolve(new ModelResolver.Options(m, jar)).model());
    }

    @Test
    void explicitFolderMissingFilesThrowsWithPath() throws Exception {
        Path m = Files.createDirectories(home.resolve("empty"));
        IOException e = assertThrows(IOException.class,
                () -> resolver(sha(MODEL)).resolve(new ModelResolver.Options(m, null)));
        assertTrue(e.getMessage().contains(m.toString()));
        assertEquals(0, calls.size());

        Path jar = home.resolve("nope.jar");
        Files.write(m.resolve("model.onnx"), MODEL);
        Files.write(m.resolve("tokenizer.json"), TOK);
        IOException e2 = assertThrows(IOException.class,
                () -> resolver(sha(MODEL)).resolve(new ModelResolver.Options(m, jar)));
        assertTrue(e2.getMessage().contains(jar.toString()));
        assertEquals(0, calls.size());
    }
}
