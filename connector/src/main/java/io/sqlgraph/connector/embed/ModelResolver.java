package io.sqlgraph.connector.embed;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.function.Consumer;

/**
 * 임베딩 모델·tokenizer·ONNX Runtime jar 위치를 정한다.
 * 지정 경로가 있으면 그대로 쓰고, 없으면 고정 URL 에서 받아 크기·sha256 으로 검증한다.
 */
public final class ModelResolver {

    public record ModelFiles(Path model, Path tokenizer, Path runtimeJar) {}

    /** 둘 다 null 가능 (null 이면 내려받는다) */
    public record Options(Path modelPath, Path runtimeJar) {}

    public interface Fetcher {
        InputStream open(URI uri) throws IOException;
    }

    /** 받을 파일 하나. relativePath 는 home 기준. */
    record Artifact(String relativePath, URI uri, long size, String sha256) {}

    private static final String HF = "https://huggingface.co/Xenova/multilingual-e5-small/resolve/"
            + "761b726dd34fb83930e26aab4e9ac3899aa1fa78/";

    static final Artifact MODEL = new Artifact("models/multilingual-e5-small/model_quantized.onnx",
            URI.create(HF + "onnx/model_quantized.onnx"), 118_308_185L,
            "f80102d3f2a1229f387d3c81909990d8945513e347b0eab049f7de3c6f98c193");
    static final Artifact TOKENIZER = new Artifact("models/multilingual-e5-small/tokenizer.json",
            URI.create(HF + "tokenizer.json"), 17_082_730L,
            "0b44a9d7b51c3c62626640cda0e2c2f70fdacdc25bbbd68038369d14ebdf4c39");
    static final Artifact RUNTIME = new Artifact("lib/onnxruntime-1.32.0.jar",
            URI.create("https://repo1.maven.org/maven2/com/microsoft/onnxruntime/onnxruntime/1.32.0/onnxruntime-1.32.0.jar"),
            55_729_095L, "5f14563a52ac9dd5166c18b18ce837fd7589d4834e76e4e552c7b053d4b0538c");

    private final Path home;
    private final Fetcher fetcher;
    private final Consumer<String> log;
    private final Artifact model;
    private final Artifact tokenizer;
    private final Artifact runtime;

    public ModelResolver(Path home, Fetcher fetcher, Consumer<String> log) {
        this(home, fetcher, log, MODEL, TOKENIZER, RUNTIME);
    }

    /** 테스트용: 받을 파일 목록(URL·크기·해시)을 바꿔 끼운다. */
    ModelResolver(Path home, Fetcher fetcher, Consumer<String> log,
                  Artifact model, Artifact tokenizer, Artifact runtime) {
        this.home = home;
        this.fetcher = fetcher == null ? httpFetcher() : fetcher;
        this.log = log == null ? s -> { } : log;
        this.model = model;
        this.tokenizer = tokenizer;
        this.runtime = runtime;
    }

    /** 기본 fetcher: HttpClient, 리다이렉트 따라감, 연결 시간 제한 30초 */
    public static Fetcher httpFetcher() {
        HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(30))
                .build();
        return uri -> {
            HttpRequest req = HttpRequest.newBuilder(uri).GET().build();
            HttpResponse<InputStream> res;
            try {
                res = client.send(req, HttpResponse.BodyHandlers.ofInputStream());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("내려받기가 중단되었습니다: " + uri, e);
            }
            if (res.statusCode() != 200) {
                res.body().close();
                throw new IOException("내려받기 실패 (HTTP " + res.statusCode() + "): " + uri);
            }
            return res.body();
        };
    }

    public ModelFiles resolve(Options o) throws IOException {
        Path modelPath = o == null ? null : o.modelPath();
        Path runtimeJar = o == null ? null : o.runtimeJar();

        Path modelFile;
        Path tokFile;
        if (modelPath != null) {
            if (!Files.isDirectory(modelPath)) {
                throw new IOException("모델 폴더를 찾을 수 없습니다: " + modelPath);
            }
            Path q = modelPath.resolve("model_quantized.onnx");
            Path plain = modelPath.resolve("model.onnx");
            if (Files.isRegularFile(q)) modelFile = q;
            else if (Files.isRegularFile(plain)) modelFile = plain;
            else throw new IOException("모델 파일(model_quantized.onnx 또는 model.onnx)이 없습니다: " + modelPath);
            tokFile = modelPath.resolve("tokenizer.json");
            if (!Files.isRegularFile(tokFile)) {
                throw new IOException("tokenizer.json 이 없습니다: " + tokFile);
            }
        } else {
            modelFile = ensure(model);
            tokFile = ensure(tokenizer);
        }

        Path jar;
        if (runtimeJar != null) {
            if (!Files.isRegularFile(runtimeJar)) {
                throw new IOException("ONNX Runtime jar 를 찾을 수 없습니다: " + runtimeJar);
            }
            jar = runtimeJar;
        } else {
            jar = ensure(runtime);
        }
        return new ModelFiles(modelFile, tokFile, jar);
    }

    private Path ensure(Artifact a) throws IOException {
        Path target = home.resolve(a.relativePath());
        if (Files.isRegularFile(target) && Files.size(target) == a.size()) {
            return target;
        }
        Files.createDirectories(target.getParent());
        Path part = target.resolveSibling(target.getFileName() + ".part");
        log.accept("내려받는 중: " + target.getFileName() + " (" + a.size() + " bytes)");

        MessageDigest md;
        try {
            md = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        try (InputStream in = fetcher.open(a.uri());
             OutputStream out = Files.newOutputStream(part)) {
            byte[] buf = new byte[64 * 1024];
            int r;
            while ((r = in.read(buf)) > 0) {
                md.update(buf, 0, r);
                out.write(buf, 0, r);
            }
        } catch (IOException e) {
            Files.deleteIfExists(part);
            throw e;
        }
        String actual = HexFormat.of().formatHex(md.digest());
        if (!actual.equalsIgnoreCase(a.sha256())) {
            Files.deleteIfExists(part);
            throw new IOException("sha256 이 맞지 않습니다 (" + target.getFileName() + "): 기대 "
                    + a.sha256() + ", 실제 " + actual);
        }
        try {
            Files.move(part, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(part, target, StandardCopyOption.REPLACE_EXISTING);
        }
        log.accept("받음: " + target.getFileName() + " (" + Files.size(target) + " bytes)");
        return target;
    }

    /** 테스트·진단용 */
    static List<Artifact> defaults() {
        return List.of(MODEL, TOKENIZER, RUNTIME);
    }
}
