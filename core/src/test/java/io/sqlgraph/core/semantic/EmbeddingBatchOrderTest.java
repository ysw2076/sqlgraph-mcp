package io.sqlgraph.core.semantic;

import io.sqlgraph.core.spi.Embedder;
import io.sqlgraph.core.store.GraphStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 새로 임베딩할 문장은 길이순으로 배치를 나눈다 (짧은 문장이 긴 문장 길이로 패딩되지 않게) */
class EmbeddingBatchOrderTest {

    @TempDir
    Path dir;

    /** 받은 배치를 기록하는 결정적 Embedder */
    static final class RecordingEmbedder implements Embedder {
        final List<List<String>> batches = new ArrayList<>();

        @Override public String modelId() { return "rec-4"; }
        @Override public int dim() { return 4; }

        @Override
        public float[][] embed(List<String> texts, Kind kind) {
            batches.add(List.copyOf(texts));
            float[][] out = new float[texts.size()][];
            for (int i = 0; i < out.length; i++) {
                int h = texts.get(i).hashCode();
                out[i] = new float[]{1 + (h & 7), 1 + ((h >> 3) & 7), 1 + ((h >> 6) & 7), texts.get(i).length()};
            }
            return out;
        }
    }

    @Test
    void batchesAreSortedByLength() throws Exception {
        try (GraphStore st = GraphStore.open(dir.resolve("g.db"))) {
            SemanticFixture.load(st, SemanticFixture.baseTables("부서"), SemanticFixture.baseStmts());
            RecordingEmbedder e = new RecordingEmbedder();
            EmbeddingIndexer.Result r = new EmbeddingIndexer().run(st, e, 2);
            assertTrue(e.batches.size() >= 2, "배치가 여러 개여야 시험이 의미 있다: " + e.batches);

            int prev = 0, total = 0;
            for (List<String> b : e.batches) {
                for (String t : b) {
                    assertTrue(t.length() >= prev, "길이가 줄어듦: " + e.batches);
                    prev = t.length();
                    total++;
                }
            }
            assertEquals(r.embedded(), total);

            // 정렬해도 저장 결과는 문서 → 벡터가 그대로 맞아야 한다: 같은 Embedder 로 다시 돌리면 전부 재사용
            EmbeddingIndexer.Result again = new EmbeddingIndexer().run(st, new RecordingEmbedder(), 2);
            assertEquals(0, again.embedded());
            assertEquals(r.docs(), again.reused());
        }
    }

    @Test
    void sortedBatchesStoreSameVectorsAsOneBigBatch() throws Exception {
        float[][] small, big;
        try (GraphStore a = GraphStore.open(dir.resolve("a.db"));
             GraphStore b = GraphStore.open(dir.resolve("b.db"))) {
            SemanticFixture.load(a, SemanticFixture.baseTables("부서"), SemanticFixture.baseStmts());
            SemanticFixture.load(b, SemanticFixture.baseTables("부서"), SemanticFixture.baseStmts());
            new EmbeddingIndexer().run(a, new RecordingEmbedder(), 1);
            new EmbeddingIndexer().run(b, new RecordingEmbedder(), 1000);
            small = vectors(a);
            big = vectors(b);
        }
        assertEquals(small.length, big.length);
        for (int i = 0; i < small.length; i++) {
            for (int j = 0; j < small[i].length; j++) assertEquals(big[i][j], small[i][j], 1e-6);
        }
    }

    private static float[][] vectors(GraphStore st) throws Exception {
        List<GraphStore.EmbVector> rows = st.embeddings();
        float[][] r = new float[rows.size()][];
        for (int i = 0; i < r.length; i++) r[i] = rows.get(i).vec();
        return r;
    }
}
