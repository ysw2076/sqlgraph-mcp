package io.sqlgraph.core.semantic;

import io.sqlgraph.core.spi.Embedder;
import io.sqlgraph.core.store.GraphStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * DB 연결이 잠깐 실패해 테이블 없이 다시 인덱싱돼도 임베딩 캐시는 지우지 않는다.
 * (실제로 회사망이 끊긴 상태에서 다시 인덱싱해 캐시 1.7만 건이 지워진 일이 있었다)
 */
class EmbeddingEmptySchemaTest {

    @TempDir
    Path dir;

    static final class CountingEmbedder implements Embedder {
        final AtomicInteger texts = new AtomicInteger();

        @Override public String modelId() { return "count-3"; }
        @Override public int dim() { return 3; }

        @Override
        public float[][] embed(List<String> t, Kind kind) {
            texts.addAndGet(t.size());
            float[][] out = new float[t.size()][];
            for (int i = 0; i < out.length; i++) out[i] = new float[]{1, t.get(i).length(), 2};
            return out;
        }
    }

    @Test
    void emptySchemaKeepsCacheAndNextReindexReusesIt() throws Exception {
        try (GraphStore st = GraphStore.open(dir.resolve("g.db"))) {
            SemanticFixture.load(st, SemanticFixture.baseTables("부서"), SemanticFixture.baseStmts());
            EmbeddingIndexer.Result first = new EmbeddingIndexer().run(st, new CountingEmbedder(), 4);
            int cache = st.embCacheCount();
            String updated = st.meta().get("emb_updated_at");

            // DB 연결 실패: 테이블 없이 다시 인덱싱
            SemanticFixture.load(st, List.of(), SemanticFixture.baseStmts());
            CountingEmbedder e = new CountingEmbedder();
            EmbeddingIndexer.Result empty = new EmbeddingIndexer().run(st, e, 4);
            assertEquals(new EmbeddingIndexer.Result(0, 0, 0, 0), empty);
            assertEquals(0, e.texts.get());
            assertEquals(cache, st.embCacheCount(), "캐시가 그대로여야 한다");
            assertEquals(updated, st.meta().get("emb_updated_at"), "아무것도 쓰지 않아야 한다");

            // DB 가 돌아옴: 전부 캐시 재사용
            SemanticFixture.load(st, SemanticFixture.baseTables("부서"), SemanticFixture.baseStmts());
            CountingEmbedder again = new CountingEmbedder();
            EmbeddingIndexer.Result back = new EmbeddingIndexer().run(st, again, 4);
            assertEquals(0, again.texts.get());
            assertEquals(first.docs(), back.reused());
        }
    }
}
