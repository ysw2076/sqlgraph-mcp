package io.sqlgraph.core.semantic;

import io.sqlgraph.core.spi.Embedder;
import io.sqlgraph.core.store.GraphStore;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;

/**
 * 임베딩 문서를 만들어 캐시에 없는 것만 임베딩하고 graph.db 의 emb/emb_cache 에 쓴다.
 * 저장은 한 트랜잭션: Embedder 가 실패하면 아무것도 바뀌지 않는다.
 */
public final class EmbeddingIndexer {

    /** 문서 형식 버전. 바꾸면 캐시가 전부 무효가 된다. */
    public static final String DOC_VERSION = "1";

    /**
     * @param docs     만든 문서 수
     * @param embedded 새로 임베딩한 문서 수
     * @param reused   캐시를 다시 쓴 문서 수
     * @param removed  지운 캐시 행 수
     */
    public record Result(int docs, int embedded, int reused, int removed) {}

    public Result run(GraphStore store, Embedder embedder, int batchSize) throws Exception {
        return run(store, embedder, batchSize, () -> false, (d, t) -> { });
    }

    /**
     * @param cancelled 배치마다 확인한다. true 면 아무것도 쓰지 않고 {@link CancellationException}
     * @param progress  (끝낸 수, 새로 임베딩할 전체 수). 시작 때 (0, total), 배치마다 누적, 끝에 (total, total)
     */
    public Result run(GraphStore store, Embedder embedder, int batchSize, BooleanSupplier cancelled,
                      BiConsumer<Integer, Integer> progress) throws Exception {
        if (cancelled == null) cancelled = () -> false;
        if (progress == null) progress = (d, t) -> { };
        if (batchSize <= 0) throw new IllegalArgumentException("batchSize 는 1 이상이어야 합니다: " + batchSize);
        String modelId = embedder.modelId();
        int dim = embedder.dim();
        if (dim <= 0) throw new IllegalStateException("Embedder 차원이 올바르지 않습니다: " + dim);

        List<DocBuilder.Doc> docs = DocBuilder.build(store);
        if (docs.isEmpty()) {
            // 테이블이 하나도 없으면(대개 DB 연결 실패로 메타데이터를 못 읽은 경우) 아무것도 쓰지 않는다.
            // 여기서 캐시를 정리하면 DB 가 돌아왔을 때 전부 다시 임베딩해야 한다.
            progress.accept(0, 0);
            return new Result(0, 0, 0, 0);
        }
        Set<String> cached = store.embCacheHashes();

        List<GraphStore.EmbRow> rows = new ArrayList<>(docs.size());
        Map<String, String> pending = new LinkedHashMap<>();   // hash → 문장 (캐시에 없는 것, 중복 제거)
        int embedded = 0, reused = 0;
        for (DocBuilder.Doc d : docs) {
            String h = hash(modelId, d.text());
            rows.add(new GraphStore.EmbRow(d.kind(), d.tblId(), d.colId(), h));
            if (cached.contains(h)) {
                reused++;
            } else {
                embedded++;
                pending.putIfAbsent(h, d.text());
            }
        }

        Map<String, float[]> vectors = new LinkedHashMap<>();
        // 짧은 문장끼리 한 배치가 되도록 길이순 정렬 (섞이면 배치 전체가 가장 긴 문장 길이로 패딩된다)
        List<Map.Entry<String, String>> byLength = new ArrayList<>(pending.entrySet());
        byLength.sort(java.util.Comparator.comparingInt(e -> e.getValue().length()));
        List<String> hashes = new ArrayList<>(byLength.size());
        List<String> texts = new ArrayList<>(byLength.size());
        for (Map.Entry<String, String> e : byLength) {
            hashes.add(e.getKey());
            texts.add(e.getValue());
        }
        int total = texts.size();
        progress.accept(0, total);
        for (int from = 0; from < texts.size(); from += batchSize) {
            if (cancelled.getAsBoolean()) throw new CancellationException("임베딩이 취소되었습니다");
            int to = Math.min(texts.size(), from + batchSize);
            List<String> batch = texts.subList(from, to);
            float[][] out = embedder.embed(List.copyOf(batch), Embedder.Kind.DOCUMENT);
            if (out == null || out.length != batch.size()) {
                throw new IllegalStateException("Embedder 결과 개수가 다릅니다: 기대 " + batch.size()
                        + ", 실제 " + (out == null ? "null" : out.length));
            }
            for (int i = 0; i < out.length; i++) {
                if (out[i] == null || out[i].length != dim) {
                    throw new IllegalStateException("Embedder 결과 차원이 다릅니다: 기대 " + dim
                            + ", 실제 " + (out[i] == null ? "null" : out[i].length));
                }
                vectors.put(hashes.get(from + i), normalize(out[i]));
            }
            if (to < total) progress.accept(to, total);
        }
        if (cancelled.getAsBoolean()) throw new CancellationException("임베딩이 취소되었습니다");

        Map<String, String> meta = new LinkedHashMap<>();
        meta.put("emb_model", modelId);
        meta.put("emb_dim", String.valueOf(dim));
        meta.put("emb_doc_version", DOC_VERSION);
        meta.put("emb_updated_at", Instant.now().toString());
        int removed = store.writeEmbeddings(vectors, rows, meta);
        progress.accept(total, total);
        return new Result(docs.size(), embedded, reused, removed);
    }

    /** sha256 hex of modelId + "\n" + DOC_VERSION + "\n" + 문장 */
    public static String hash(String modelId, String text) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest((modelId + "\n" + DOC_VERSION + "\n" + text).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(d);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** L2 정규화한 복사본 (크기 0 이면 그대로) */
    static float[] normalize(float[] v) {
        double s = 0;
        for (float f : v) s += (double) f * f;
        float[] r = v.clone();
        if (s == 0) return r;
        double n = Math.sqrt(s);
        for (int i = 0; i < r.length; i++) r[i] = (float) (r[i] / n);
        return r;
    }
}
