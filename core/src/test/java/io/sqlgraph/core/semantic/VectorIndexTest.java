package io.sqlgraph.core.semantic;

import io.sqlgraph.core.store.GraphStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VectorIndexTest {

    @TempDir
    Path dir;

    private GraphStore openWithVectors() throws Exception {
        GraphStore store = GraphStore.open(dir.resolve("graph.db"));
        Map<String, float[]> vecs = new LinkedHashMap<>();
        vecs.put("h2", new float[]{0, 1, 0});
        vecs.put("h1c10", new float[]{0.8f, 0.6f, 0});
        vecs.put("h3", new float[]{2, 0, 0});        // 정규화 전 크기 2 → 적재 때 정규화
        vecs.put("h1", new float[]{1, 0, 0});
        vecs.put("h1c5", new float[]{1, 0, 0});
        List<GraphStore.EmbRow> rows = List.of(
                new GraphStore.EmbRow("t", 2, null, "h2"),
                new GraphStore.EmbRow("c", 1, 10L, "h1c10"),
                new GraphStore.EmbRow("t", 3, null, "h3"),
                new GraphStore.EmbRow("c", 1, 5L, "h1c5"),
                new GraphStore.EmbRow("t", 1, null, "h1"));
        store.writeEmbeddings(vecs, rows, Map.of("emb_dim", "3"));
        return store;
    }

    @Test
    void ranksByCosineWithTieOrder() throws Exception {
        try (GraphStore store = openWithVectors()) {
            VectorIndex idx = VectorIndex.load(store);
            assertEquals(5, idx.size());
            assertEquals(3, idx.dim());

            List<VectorIndex.Hit> hits = idx.topK(new float[]{1, 0, 0}, 10);
            assertEquals(5, hits.size());
            // 동점(1.0): tblId 오름차순, 같은 테이블은 colId null 먼저
            assertEquals(1, hits.get(0).tblId());
            assertNull(hits.get(0).colId());
            assertEquals("t", hits.get(0).kind());
            assertEquals(1, hits.get(1).tblId());
            assertEquals(5L, hits.get(1).colId());
            assertEquals(3, hits.get(2).tblId());
            assertEquals(1.0f, hits.get(2).score(), 1e-5);
            assertEquals(10L, hits.get(3).colId());
            assertEquals(0.8f, hits.get(3).score(), 1e-5);
            assertEquals(2, hits.get(4).tblId());
            assertEquals(0f, hits.get(4).score(), 1e-5);

            List<VectorIndex.Hit> top2 = idx.topK(new float[]{1, 0, 0}, 2);
            assertEquals(hits.subList(0, 2), top2);
        }
    }

    @Test
    void queryIsNormalized() throws Exception {
        try (GraphStore store = openWithVectors()) {
            VectorIndex idx = VectorIndex.load(store);
            assertEquals(idx.topK(new float[]{0.75f, 1f, 0}, 5), idx.topK(new float[]{3f, 4f, 0}, 5));
            VectorIndex.Hit first = idx.topK(new float[]{3f, 4f, 0}, 1).get(0);
            assertEquals(10L, first.colId());
            assertTrue(first.score() <= 1.0001f);
        }
    }

    @Test
    void emptyIndexReturnsEmpty() throws Exception {
        try (GraphStore store = GraphStore.open(dir.resolve("empty.db"))) {
            VectorIndex idx = VectorIndex.load(store);
            assertEquals(0, idx.size());
            assertEquals(List.of(), idx.topK(new float[]{1, 0, 0}, 5));
        }
    }

    @Test
    void dimensionMismatchThrows() throws Exception {
        try (GraphStore store = openWithVectors()) {
            VectorIndex idx = VectorIndex.load(store);
            assertThrows(IllegalArgumentException.class, () -> idx.topK(new float[]{1, 0}, 5));
        }
    }
}
