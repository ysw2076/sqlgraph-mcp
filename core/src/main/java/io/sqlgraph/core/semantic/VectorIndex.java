package io.sqlgraph.core.semantic;

import io.sqlgraph.core.store.GraphStore;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** emb + emb_cache 를 메모리에 올려 전수 내적으로 top-K 를 찾는다. */
public final class VectorIndex {

    /** @param colId 컬럼 문서일 때만, 테이블이면 null */
    public record Hit(String kind, long tblId, Long colId, float score) {}

    /** 점수 내림차순 → tblId 오름차순 → colId (null 먼저) 오름차순 */
    static final Comparator<Hit> ORDER = Comparator.comparing((Hit h) -> h.score(), Comparator.reverseOrder())
            .thenComparingLong(Hit::tblId)
            .thenComparing(Hit::colId, Comparator.nullsFirst(Comparator.naturalOrder()));

    private final int dim;
    private final float[] data;      // size × dim, 각 행은 L2 정규화
    private final String[] kinds;
    private final long[] tblIds;
    private final Long[] colIds;

    private VectorIndex(int dim, float[] data, String[] kinds, long[] tblIds, Long[] colIds) {
        this.dim = dim;
        this.data = data;
        this.kinds = kinds;
        this.tblIds = tblIds;
        this.colIds = colIds;
    }

    public static VectorIndex load(GraphStore store) throws SQLException {
        List<GraphStore.EmbVector> rows = store.embeddings();
        int dim;
        if (rows.isEmpty()) {
            dim = parseInt(store.meta().get("emb_dim"));
        } else {
            dim = rows.get(0).vec().length;
        }
        int n = rows.size();
        float[] data = new float[n * dim];
        String[] kinds = new String[n];
        long[] tblIds = new long[n];
        Long[] colIds = new Long[n];
        for (int i = 0; i < n; i++) {
            GraphStore.EmbVector r = rows.get(i);
            if (r.vec().length != dim) {
                throw new IllegalStateException("저장된 벡터 차원이 섞여 있습니다: " + dim + " / " + r.vec().length);
            }
            float[] v = EmbeddingIndexer.normalize(r.vec());
            System.arraycopy(v, 0, data, i * dim, dim);
            kinds[i] = r.kind();
            tblIds[i] = r.tblId();
            colIds[i] = r.colId();
        }
        return new VectorIndex(dim, data, kinds, tblIds, colIds);
    }

    public int size() {
        return kinds.length;
    }

    public int dim() {
        return dim;
    }

    public List<Hit> topK(float[] query, int k) {
        if (size() == 0 || k <= 0) return List.of();
        if (query == null || query.length != dim) {
            throw new IllegalArgumentException("질의 벡터 차원이 다릅니다: 기대 " + dim
                    + ", 실제 " + (query == null ? "null" : query.length));
        }
        float[] q = EmbeddingIndexer.normalize(query);
        List<Hit> all = new ArrayList<>(size());
        for (int i = 0; i < size(); i++) {
            int off = i * dim;
            float s = 0;
            for (int j = 0; j < dim; j++) s += data[off + j] * q[j];
            all.add(new Hit(kinds[i], tblIds[i], colIds[i], s));
        }
        all.sort(ORDER);
        return List.copyOf(all.subList(0, Math.min(k, all.size())));
    }

    private static int parseInt(String s) {
        try {
            return s == null ? 0 : Integer.parseInt(s.strip());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
