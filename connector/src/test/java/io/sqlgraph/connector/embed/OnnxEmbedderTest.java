package io.sqlgraph.connector.embed;

import io.sqlgraph.core.spi.Embedder;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OnnxEmbedderTest {

    /** 벡터 = [실제 토큰 수, 실제 토큰 id 합]. 호출 입력을 기록한다. */
    static class FakeRunner implements PooledRunner {
        final List<long[][]> ids = new ArrayList<>();
        final List<long[][]> masks = new ArrayList<>();
        int dim = 2;
        boolean closed;

        @Override
        public float[][] run(long[][] inputIds, long[][] attentionMask) {
            ids.add(inputIds);
            masks.add(attentionMask);
            float[][] out = new float[inputIds.length][];
            for (int i = 0; i < inputIds.length; i++) {
                float n = 0, sum = 0;
                for (int t = 0; t < inputIds[i].length; t++) {
                    if (attentionMask[i][t] == 1) {
                        n++;
                        sum += inputIds[i][t];
                    }
                }
                float[] v = new float[dim];
                v[0] = n;
                if (dim > 1) v[1] = sum;
                out[i] = v;
            }
            return out;
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    private static long[] toLong(int[] a) {
        long[] r = new long[a.length];
        for (int i = 0; i < a.length; i++) r[i] = a[i];
        return r;
    }

    @Test
    void prefixesByKind() throws Exception {
        UnigramTokenizer tok = UnigramTokenizerTest.tok();
        FakeRunner r = new FakeRunner();
        OnnxEmbedder e = new OnnxEmbedder(tok, r, "m", 2, 32);

        e.embed(List.of("abc"), Embedder.Kind.QUERY);
        e.embed(List.of("abc"), Embedder.Kind.DOCUMENT);

        assertArrayEquals(toLong(tok.encode("query: abc", 512)), r.ids.get(0)[0]);
        assertArrayEquals(toLong(tok.encode("passage: abc", 512)), r.ids.get(1)[0]);
        assertTrue(r.ids.get(0)[0].length != r.ids.get(1)[0].length
                || !java.util.Arrays.equals(r.ids.get(0)[0], r.ids.get(1)[0]));
        assertEquals("m", e.modelId());
        assertEquals(2, e.dim());
    }

    @Test
    void resultsReturnInInputOrder() throws Exception {
        UnigramTokenizer tok = UnigramTokenizerTest.tok();
        FakeRunner r = new FakeRunner();
        OnnxEmbedder e = new OnnxEmbedder(tok, r, "m", 2, 32);
        List<String> in = List.of("x x x x", "x", "x x x", "x x");

        float[][] out = e.embed(in, Embedder.Kind.DOCUMENT);

        assertEquals(4, out.length);
        for (int i = 0; i < in.size(); i++) {
            int[] enc = tok.encode("passage: " + in.get(i), 512);
            assertEquals(enc.length, (int) out[i][0], "입력 " + i);
        }
        // 러너에는 길이 오름차순으로 들어간다
        long[][] mask = r.masks.get(0);
        for (int k = 1; k < mask.length; k++) {
            assertTrue(count(mask[k - 1]) <= count(mask[k]));
        }
    }

    private static int count(long[] m) {
        int n = 0;
        for (long v : m) n += (int) v;
        return n;
    }

    @Test
    void paddingUsesPadIdAndZeroMask() throws Exception {
        UnigramTokenizer tok = UnigramTokenizerTest.tok();
        FakeRunner r = new FakeRunner();
        OnnxEmbedder e = new OnnxEmbedder(tok, r, "m", 2, 32);

        e.embed(List.of("x", "x x x"), Embedder.Kind.DOCUMENT);

        long[][] ids = r.ids.get(0);
        long[][] mask = r.masks.get(0);
        int shortLen = tok.encode("passage: x", 512).length;
        int longLen = tok.encode("passage: x x x", 512).length;
        assertEquals(longLen, ids[0].length);
        assertEquals(longLen, ids[1].length);
        for (int t = 0; t < longLen; t++) {
            if (t < shortLen) {
                assertEquals(1, mask[0][t]);
            } else {
                assertEquals(0, mask[0][t]);
                assertEquals(tok.padId(), ids[0][t]);
            }
            assertEquals(1, mask[1][t]);
        }
    }

    @Test
    void splitsIntoBatches() throws Exception {
        FakeRunner r = new FakeRunner();
        OnnxEmbedder e = new OnnxEmbedder(UnigramTokenizerTest.tok(), r, "m", 2, 2);

        float[][] out = e.embed(List.of("x", "x x", "x x x", "x x x x", "abc"), Embedder.Kind.DOCUMENT);

        assertEquals(5, out.length);
        assertEquals(3, r.ids.size());
        assertEquals(2, r.ids.get(0).length);
        assertEquals(2, r.ids.get(1).length);
        assertEquals(1, r.ids.get(2).length);
    }

    @Test
    void dimensionMismatchThrows() throws Exception {
        FakeRunner r = new FakeRunner();
        r.dim = 3;
        OnnxEmbedder e = new OnnxEmbedder(UnigramTokenizerTest.tok(), r, "m", 2, 32);
        assertThrows(IllegalStateException.class, () -> e.embed(List.of("x"), Embedder.Kind.DOCUMENT));
    }

    @Test
    void countMismatchThrows() throws Exception {
        PooledRunner r = new PooledRunner() {
            @Override
            public float[][] run(long[][] inputIds, long[][] attentionMask) {
                return new float[0][];
            }

            @Override
            public void close() {
            }
        };
        OnnxEmbedder e = new OnnxEmbedder(UnigramTokenizerTest.tok(), r, "m", 2, 32);
        assertThrows(IllegalStateException.class, () -> e.embed(List.of("x"), Embedder.Kind.DOCUMENT));
    }

    @Test
    void emptyInputSkipsRunner() throws Exception {
        FakeRunner r = new FakeRunner();
        OnnxEmbedder e = new OnnxEmbedder(UnigramTokenizerTest.tok(), r, "m", 2, 32);
        assertEquals(0, e.embed(List.of(), Embedder.Kind.QUERY).length);
        assertEquals(0, r.ids.size());
        e.close();
        assertTrue(r.closed);
    }
}
