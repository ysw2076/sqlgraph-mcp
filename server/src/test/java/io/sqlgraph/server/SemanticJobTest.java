package io.sqlgraph.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.sqlgraph.core.spi.Embedder;
import io.sqlgraph.core.store.GraphStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 인덱싱 뒤 백그라운드 임베딩 (가짜 Embedder 공장, 실제 모델·네트워크 없음) */
class SemanticJobTest {

    static final Path HR = Path.of("../samples/hr-project").toAbsolutePath().normalize();
    static final Duration WAIT = Duration.ofSeconds(30);
    static final ObjectMapper om = new ObjectMapper();

    @TempDir
    Path home;

    Workspace ws;

    /** 결정적 해시 벡터 (dim 16) */
    static class HashEmbedder implements Embedder {
        @Override public String modelId() { return "fake-hash-16"; }
        @Override public int dim() { return 16; }

        @Override
        public float[][] embed(List<String> texts, Kind kind) throws Exception {
            float[][] out = new float[texts.size()][];
            for (int i = 0; i < out.length; i++) {
                byte[] h = MessageDigest.getInstance("SHA-256").digest(texts.get(i).getBytes(StandardCharsets.UTF_8));
                float[] v = new float[16];
                for (int j = 0; j < 16; j++) v[j] = (h[j] & 0xff) + 1;
                out[i] = v;
            }
            return out;
        }
    }

    /** 첫 embed 에서 래치를 기다리며 멈추는 Embedder */
    static final class BlockingEmbedder extends HashEmbedder implements AutoCloseable {
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final AtomicInteger completed = new AtomicInteger();
        volatile boolean closed;

        @Override
        public float[][] embed(List<String> texts, Kind kind) throws Exception {
            entered.countDown();
            release.await();
            float[][] r = super.embed(texts, kind);
            completed.incrementAndGet();
            return r;
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    private Workspace workspace(EmbedderFactory f, boolean semantic) throws Exception {
        SqlGraphHome h = new SqlGraphHome(home);
        ws = new Workspace(h, Path.of(".").toAbsolutePath(), s -> { }, f);
        if (semantic) {
            Config c = Config.load(HR.resolve("sqlgraph.yml"), h.indexFor(HR))
                    .withSemantic(new Config.Semantic(true, null, null, 0));
            ws.pin(c);
        }
        return ws;
    }

    private String status() throws Exception {
        return new Tools(ws, om).call("index_status", om.readTree("{\"project\":\"hr-project\"}"));
    }

    @AfterEach
    void tearDown() throws Exception {
        if (ws != null) ws.close();
    }

    @Test
    void enabledBuildsEmbeddingsInBackground() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        workspace((s, log) -> {
            calls.incrementAndGet();
            return new HashEmbedder();
        }, true);
        String out = new Tools(ws, om).indexProject(HR.toString(), null, false);
        assertTrue(out.contains("의미 검색: 백그라운드에서 임베딩을 시작했습니다"), out);

        SqlGraphService svc = ws.service(HR);
        assertTrue(svc.awaitSemantic(WAIT));
        assertEquals(SqlGraphService.SemanticState.READY, svc.semanticStatus().state());
        assertEquals(1, calls.get());
        try (GraphStore st = GraphStore.open(svc.config().index())) {
            assertTrue(st.embCount() > 0);
            assertEquals("fake-hash-16", st.meta().get("emb_model"));
        }
        String s = status();
        assertTrue(s.contains("- 의미 검색: 준비됨 — 모델 fake-hash-16"), s);
    }

    @Test
    void disabledNeverCallsFactory() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        workspace((s, log) -> {
            calls.incrementAndGet();
            return new HashEmbedder();
        }, false);
        String out = new Tools(ws, om).indexProject(HR.toString(), null, false);
        assertFalse(out.contains("의미 검색"), out);
        SqlGraphService svc = ws.service(HR);
        assertTrue(svc.awaitSemantic(WAIT));
        assertEquals(0, calls.get());
        assertEquals(SqlGraphService.SemanticState.OFF, svc.semanticStatus().state());
        String s = status();
        assertTrue(s.contains("- 의미 검색: 꺼짐"), s);
    }

    @Test
    void factoryFailureIsReportedButIndexingSucceeds() throws Exception {
        workspace((s, log) -> {
            throw new IllegalStateException("모델을 찾을 수 없음(테스트)");
        }, true);
        String out = new Tools(ws, om).indexProject(HR.toString(), null, false);
        assertTrue(out.contains("인덱싱 완료"), out);
        SqlGraphService svc = ws.service(HR);
        assertTrue(svc.awaitSemantic(WAIT));
        SqlGraphService.SemanticStatus st = svc.semanticStatus();
        assertEquals(SqlGraphService.SemanticState.FAILED, st.state());
        String s = status();
        assertTrue(s.contains("- 의미 검색: 실패: 모델을 찾을 수 없음(테스트)"), s);
    }

    @Test
    void reindexCancelsRunningJob() throws Exception {
        BlockingEmbedder blocking = new BlockingEmbedder();
        AtomicInteger calls = new AtomicInteger();
        workspace((s, log) -> calls.incrementAndGet() == 1 ? blocking : new HashEmbedder(), true);
        try {
            ws.index(HR, null, false);
            assertTrue(blocking.entered.await(WAIT.toSeconds(), TimeUnit.SECONDS), "첫 작업이 embed 에 들어감");
            SqlGraphService first = ws.service(HR);
            assertEquals(SqlGraphService.SemanticState.RUNNING, first.semanticStatus().state());

            assertTimeoutPreemptively(WAIT, () -> ws.index(HR, null, false));

            assertTrue(first.awaitSemantic(WAIT));
            assertEquals(SqlGraphService.SemanticState.CANCELLED, first.semanticStatus().state());
            assertEquals(0, blocking.completed.get(), "취소된 작업은 아무것도 쓰지 않음");
            assertTrue(blocking.closed, "Embedder 는 닫힘");

            SqlGraphService second = ws.service(HR);
            assertTrue(second != first);
            assertTrue(second.awaitSemantic(WAIT));
            assertEquals(SqlGraphService.SemanticState.READY, second.semanticStatus().state());
            assertEquals(2, calls.get());
        } finally {
            blocking.release.countDown();
        }
    }

    @Test
    void closeWhileRunningFinishesInTime() throws Exception {
        BlockingEmbedder blocking = new BlockingEmbedder();
        workspace((s, log) -> blocking, true);
        try {
            ws.index(HR, null, false);
            assertTrue(blocking.entered.await(WAIT.toSeconds(), TimeUnit.SECONDS));
            SqlGraphService svc = ws.service(HR);
            assertTimeoutPreemptively(WAIT, () -> ws.close());
            assertTrue(svc.awaitSemantic(WAIT));
            assertEquals(SqlGraphService.SemanticState.CANCELLED, svc.semanticStatus().state());
        } finally {
            blocking.release.countDown();
        }
    }
}
