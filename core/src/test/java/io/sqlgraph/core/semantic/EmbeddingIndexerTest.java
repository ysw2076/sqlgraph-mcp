package io.sqlgraph.core.semantic;

import io.sqlgraph.core.spi.Embedder;
import io.sqlgraph.core.store.GraphStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EmbeddingIndexerTest {

    @TempDir
    Path dir;

    /** 문장 해시로 벡터를 만드는 결정적 가짜 Embedder. 받은 문장을 기록한다. */
    static final class FakeEmbedder implements Embedder {
        final String model;
        final int dim;
        final int outDim;
        final int dropCount;
        final boolean fail;
        final List<String> seen = new ArrayList<>();

        FakeEmbedder(String model) {
            this(model, 8, 8, 0, false);
        }

        FakeEmbedder(String model, int dim, int outDim, int dropCount, boolean fail) {
            this.model = model;
            this.dim = dim;
            this.outDim = outDim;
            this.dropCount = dropCount;
            this.fail = fail;
        }

        @Override public String modelId() { return model; }
        @Override public int dim() { return dim; }

        @Override
        public float[][] embed(List<String> texts, Kind kind) throws Exception {
            assertEquals(Kind.DOCUMENT, kind);
            if (fail) throw new RuntimeException("embed 실패");
            seen.addAll(texts);
            float[][] out = new float[Math.max(0, texts.size() - dropCount)][];
            for (int i = 0; i < out.length; i++) {
                byte[] h = MessageDigest.getInstance("SHA-256").digest(texts.get(i).getBytes(StandardCharsets.UTF_8));
                float[] v = new float[outDim];
                for (int j = 0; j < outDim; j++) v[j] = (h[j % h.length] & 0xff) + 1;
                out[i] = v;
            }
            return out;
        }
    }

    private GraphStore openLoaded(String deptComment) throws Exception {
        GraphStore store = GraphStore.open(dir.resolve("graph.db"));
        SemanticFixture.load(store, SemanticFixture.baseTables(deptComment), SemanticFixture.baseStmts());
        return store;
    }

    @Test
    void firstRunEmbedsAll() throws Exception {
        try (GraphStore store = openLoaded("부서")) {
            FakeEmbedder e = new FakeEmbedder("fake-a");
            EmbeddingIndexer.Result r = new EmbeddingIndexer().run(store, e, 3);
            assertEquals(3 + 5, r.docs(), "테이블 3 + 코멘트 있는 컬럼 5");
            assertEquals(r.docs(), r.embedded());
            assertEquals(0, r.reused());
            assertEquals(0, r.removed());
            assertEquals(8, e.seen.size());
            assertEquals(8, store.embCount());
            assertEquals(8, store.embCacheCount());
            Map<String, String> meta = store.meta();
            assertEquals("fake-a", meta.get("emb_model"));
            assertEquals("8", meta.get("emb_dim"));
            assertEquals("1", meta.get("emb_doc_version"));

            // 저장된 벡터: dim×4 바이트, L2 정규화
            for (GraphStore.EmbVector v : store.embeddings()) {
                assertEquals(8, v.vec().length);
                double s = 0;
                for (float f : v.vec()) s += f * f;
                assertEquals(1.0, s, 1e-5);
            }
        }
    }

    @Test
    void reindexSameDataReusesCache() throws Exception {
        try (GraphStore store = openLoaded("부서")) {
            new EmbeddingIndexer().run(store, new FakeEmbedder("fake-a"), 4);
            SemanticFixture.load(store, SemanticFixture.baseTables("부서"), SemanticFixture.baseStmts());
            assertEquals(0, store.embCount(), "replaceAll 은 emb 를 비운다");
            assertEquals(8, store.embCacheCount(), "replaceAll 은 emb_cache 를 지우지 않는다");

            FakeEmbedder e = new FakeEmbedder("fake-a");
            EmbeddingIndexer.Result r = new EmbeddingIndexer().run(store, e, 4);
            assertEquals(0, r.embedded());
            assertEquals(r.docs(), r.reused());
            assertEquals(0, r.removed());
            assertEquals(0, e.seen.size());
            assertEquals(8, store.embCount());
        }
    }

    @Test
    void changedCommentReembedsOnlyThatTable() throws Exception {
        try (GraphStore store = openLoaded("부서")) {
            new EmbeddingIndexer().run(store, new FakeEmbedder("fake-a"), 4);
            SemanticFixture.load(store, SemanticFixture.baseTables("부서정보"), SemanticFixture.baseStmts());

            FakeEmbedder e = new FakeEmbedder("fake-a");
            EmbeddingIndexer.Result r = new EmbeddingIndexer().run(store, e, 4);
            assertEquals(3, r.embedded(), "HR_DEPT 테이블 문서 + 컬럼 문서 2");
            assertEquals(5, r.reused());
            assertEquals(3, r.removed(), "옛 문장 캐시 제거");
            assertEquals(List.of(
                    "부서정보. 테이블 HR_DEPT. 컬럼: 부서코드, 부서명",
                    "부서정보 > 부서코드 (DEPT CD)",
                    "부서정보 > 부서명 (DEPT NM)"), e.seen);
            assertEquals(8, store.embCacheCount());
            assertEquals(8, store.embCount());
        }
    }

    @Test
    void modelChangeReembedsEverything() throws Exception {
        try (GraphStore store = openLoaded("부서")) {
            new EmbeddingIndexer().run(store, new FakeEmbedder("fake-a"), 4);
            FakeEmbedder e = new FakeEmbedder("fake-b");
            EmbeddingIndexer.Result r = new EmbeddingIndexer().run(store, e, 4);
            assertEquals(8, r.embedded());
            assertEquals(0, r.reused());
            assertEquals(8, r.removed());
            assertEquals(8, store.embCacheCount());
            assertEquals("fake-b", store.meta().get("emb_model"));
        }
    }

    @Test
    void badEmbedderLeavesStateUntouched() throws Exception {
        try (GraphStore store = openLoaded("부서")) {
            new EmbeddingIndexer().run(store, new FakeEmbedder("fake-a"), 4);
            Map<String, String> metaBefore = store.meta();
            int embBefore = store.embCount();
            int cacheBefore = store.embCacheCount();

            List<FakeEmbedder> bad = List.of(
                    new FakeEmbedder("fake-b", 8, 7, 0, false),   // 차원 틀림
                    new FakeEmbedder("fake-b", 8, 8, 1, false),   // 개수 틀림
                    new FakeEmbedder("fake-b", 8, 8, 0, true));   // 예외
            for (FakeEmbedder e : bad) {
                assertThrows(Exception.class, () -> new EmbeddingIndexer().run(store, e, 3));
                assertEquals(embBefore, store.embCount());
                assertEquals(cacheBefore, store.embCacheCount());
                assertEquals(metaBefore, store.meta());
            }

            // 그 뒤에도 정상 실행은 캐시를 그대로 쓴다
            EmbeddingIndexer.Result r = new EmbeddingIndexer().run(store, new FakeEmbedder("fake-a"), 4);
            assertEquals(8, r.reused());
            assertTrue(r.embedded() == 0);
        }
    }
}
