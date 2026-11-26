package io.sqlgraph.core.semantic;

import io.sqlgraph.core.spi.Embedder;
import io.sqlgraph.core.store.GraphStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EmbeddingIndexerCancelTest {

    @TempDir
    Path dir;

    /** 결정적 해시 벡터, 호출 횟수를 센다 */
    static final class HashEmbedder implements Embedder {
        final String model;
        int calls;

        HashEmbedder(String model) {
            this.model = model;
        }

        @Override public String modelId() { return model; }
        @Override public int dim() { return 8; }

        @Override
        public float[][] embed(List<String> texts, Kind kind) throws Exception {
            calls++;
            float[][] out = new float[texts.size()][];
            for (int i = 0; i < out.length; i++) {
                byte[] h = MessageDigest.getInstance("SHA-256").digest(texts.get(i).getBytes(StandardCharsets.UTF_8));
                float[] v = new float[8];
                for (int j = 0; j < 8; j++) v[j] = (h[j] & 0xff) + 1;
                out[i] = v;
            }
            return out;
        }
    }

    private GraphStore loaded() throws Exception {
        GraphStore store = GraphStore.open(dir.resolve("graph.db"));
        SemanticFixture.load(store, SemanticFixture.baseTables("부서"), SemanticFixture.baseStmts());
        return store;
    }

    @Test
    void cancelAfterFirstBatchLeavesEverythingUnchanged() throws Exception {
        try (GraphStore store = loaded()) {
            new EmbeddingIndexer().run(store, new HashEmbedder("model-a"), 2);
            int embBefore = store.embCount();
            Set<String> cacheBefore = store.embCacheHashes();
            Map<String, String> metaBefore = store.meta();
            assertTrue(embBefore > 0);

            HashEmbedder b = new HashEmbedder("model-b");
            assertThrows(CancellationException.class,
                    () -> new EmbeddingIndexer().run(store, b, 2, () -> b.calls >= 1, (d, t) -> { }));
            assertEquals(1, b.calls, "첫 배치 뒤 멈춤");
            assertEquals(embBefore, store.embCount());
            assertEquals(cacheBefore, store.embCacheHashes());
            assertEquals(metaBefore, store.meta());
        }
    }

    @Test
    void progressIsMonotonicFromZeroToTotal() throws Exception {
        try (GraphStore store = loaded()) {
            List<int[]> seen = new ArrayList<>();
            EmbeddingIndexer.Result r = new EmbeddingIndexer().run(store, new HashEmbedder("m"), 3,
                    () -> false, (d, t) -> seen.add(new int[]{d, t}));
            assertTrue(seen.size() >= 2);
            int total = seen.get(0)[1];
            assertTrue(total > 0);
            assertTrue(total <= r.embedded(), "중복 문장은 한 번만 임베딩");
            assertEquals(0, seen.get(0)[0]);
            int prev = -1;
            for (int[] p : seen) {
                assertEquals(total, p[1]);
                assertTrue(p[0] >= prev, "단조 증가");
                assertTrue(p[0] <= total);
                prev = p[0];
            }
            assertEquals(total, seen.get(seen.size() - 1)[0]);

            // 다시 돌리면 새로 임베딩할 것이 없다: (0,0) → (0,0)
            List<int[]> again = new ArrayList<>();
            new EmbeddingIndexer().run(store, new HashEmbedder("m"), 3, () -> false,
                    (d, t) -> again.add(new int[]{d, t}));
            assertEquals(0, again.get(0)[1]);
            assertEquals(0, again.get(again.size() - 1)[0]);
        }
    }

    @Test
    void recordsUpdatedAt() throws Exception {
        try (GraphStore store = loaded()) {
            Instant before = Instant.now().minusSeconds(1);
            new EmbeddingIndexer().run(store, new HashEmbedder("m"), 4);
            String v = store.meta().get("emb_updated_at");
            assertNotNull(v);
            Instant at = Instant.parse(v);
            assertTrue(!at.isBefore(before));
        }
    }
}
