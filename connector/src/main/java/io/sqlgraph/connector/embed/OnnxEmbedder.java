package io.sqlgraph.connector.embed;

import io.sqlgraph.core.spi.Embedder;

import java.io.IOException;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/** multilingual-e5 계열 ONNX 임베딩. 벡터 정규화는 core 가 한다. */
public final class OnnxEmbedder implements Embedder, AutoCloseable {

    public static final String DEFAULT_MODEL_ID = "multilingual-e5-small-int8@761b726";
    public static final int DEFAULT_DIM = 384;
    public static final int DEFAULT_BATCH = 32;
    static final int MAX_TOKENS = 512;

    private final UnigramTokenizer tok;
    private final PooledRunner runner;
    private final String modelId;
    private final int dim;
    private final int batchSize;

    public OnnxEmbedder(UnigramTokenizer tok, PooledRunner runner, String modelId, int dim, int batchSize) {
        if (batchSize <= 0) throw new IllegalArgumentException("batchSize 는 1 이상이어야 합니다: " + batchSize);
        this.tok = tok;
        this.runner = runner;
        this.modelId = modelId;
        this.dim = dim;
        this.batchSize = batchSize;
    }

    public static OnnxEmbedder open(ModelResolver.ModelFiles files, int threads) throws IOException {
        UnigramTokenizer tok = UnigramTokenizer.load(files.tokenizer());
        OrtRunner runner = OrtRunner.open(files.runtimeJar(), files.model(), threads);
        return new OnnxEmbedder(tok, runner, DEFAULT_MODEL_ID, DEFAULT_DIM, DEFAULT_BATCH);
    }

    @Override
    public String modelId() {
        return modelId;
    }

    @Override
    public int dim() {
        return dim;
    }

    @Override
    public float[][] embed(List<String> texts, Kind kind) throws Exception {
        int n = texts.size();
        if (n == 0) return new float[0][];
        String prefix = kind == Kind.QUERY ? "query: " : "passage: ";
        int[][] enc = new int[n][];
        for (int i = 0; i < n; i++) enc[i] = tok.encode(prefix + texts.get(i), MAX_TOKENS);

        Integer[] order = new Integer[n];
        for (int i = 0; i < n; i++) order[i] = i;
        Arrays.sort(order, Comparator.comparingInt(i -> enc[i].length));   // 안정 정렬

        float[][] out = new float[n][];
        long pad = tok.padId();
        for (int from = 0; from < n; from += batchSize) {
            int to = Math.min(n, from + batchSize);
            int b = to - from;
            int len = 0;
            for (int k = from; k < to; k++) len = Math.max(len, enc[order[k]].length);
            long[][] ids = new long[b][len];
            long[][] mask = new long[b][len];
            for (int k = 0; k < b; k++) {
                int[] e = enc[order[from + k]];
                Arrays.fill(ids[k], pad);
                for (int t = 0; t < e.length; t++) {
                    ids[k][t] = e[t];
                    mask[k][t] = 1;
                }
            }
            float[][] res = runner.run(ids, mask);
            if (res == null || res.length != b) {
                throw new IllegalStateException("러너 결과 개수가 다릅니다: 기대 " + b
                        + ", 실제 " + (res == null ? "null" : res.length));
            }
            for (int k = 0; k < b; k++) {
                if (res[k] == null || res[k].length != dim) {
                    throw new IllegalStateException("러너 결과 차원이 다릅니다: 기대 " + dim
                            + ", 실제 " + (res[k] == null ? "null" : res[k].length));
                }
                out[order[from + k]] = res[k];
            }
        }
        return out;
    }

    @Override
    public void close() throws Exception {
        runner.close();
    }
}
