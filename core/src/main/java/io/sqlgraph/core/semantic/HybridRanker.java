package io.sqlgraph.core.semantic;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 키워드 순위와 의미 순위를 합친다 (DB 를 모르는 순수 함수).
 * 이름 정확 일치 맨 위 → RRF Σ 1/(k + rank) 내림차순 → tblId 오름차순.
 */
public final class HybridRanker {

    public static final int DEFAULT_RRF_K = 60;

    /**
     * @param keywordRank   키워드 순위 (1부터), 없으면 null
     * @param semanticScore 테이블별로 묶은 의미 점수, 없으면 null
     * @param bestColId     의미 점수가 컬럼 Hit 에서 왔으면 그 컬럼 id
     */
    public record Ranked(long tblId, double score, boolean exact, Integer keywordRank, Float semanticScore,
                         Long bestColId) {}

    private HybridRanker() {}

    public static List<Ranked> rank(List<Long> keywordIds, List<VectorIndex.Hit> semanticHits, Set<Long> exactIds,
                                    float threshold, int limit) {
        return rank(keywordIds, semanticHits, exactIds, threshold, DEFAULT_RRF_K, limit);
    }

    public static List<Ranked> rank(List<Long> keywordIds, List<VectorIndex.Hit> semanticHits, Set<Long> exactIds,
                                    float threshold, int rrfK, int limit) {
        if (limit <= 0) return List.of();
        List<Long> kw = keywordIds == null ? List.of() : keywordIds;
        List<VectorIndex.Hit> hits = semanticHits == null ? List.of() : semanticHits;
        Set<Long> exact = exactIds == null ? Set.of() : exactIds;

        // 키워드 순위 (중복은 첫 위치)
        Map<Long, Integer> kwRank = new LinkedHashMap<>();
        for (Long id : kw) {
            if (id != null) kwRank.putIfAbsent(id, kwRank.size() + 1);
        }

        // 의미: 문턱 미만 제외, 테이블별 최고 점수 하나
        Map<Long, VectorIndex.Hit> best = new HashMap<>();
        for (VectorIndex.Hit h : hits) {
            if (h == null || !(h.score() >= threshold)) continue;
            best.merge(h.tblId(), h, (a, b) -> VectorIndex.ORDER.compare(a, b) <= 0 ? a : b);
        }
        List<VectorIndex.Hit> grouped = new ArrayList<>(best.values());
        grouped.sort(VectorIndex.ORDER);
        Map<Long, Integer> semRank = new HashMap<>();
        for (int i = 0; i < grouped.size(); i++) semRank.put(grouped.get(i).tblId(), i + 1);

        Set<Long> ids = new LinkedHashSet<>(kwRank.keySet());
        for (VectorIndex.Hit h : grouped) ids.add(h.tblId());
        for (Long id : exact) if (id != null) ids.add(id);

        List<Ranked> out = new ArrayList<>(ids.size());
        for (long id : ids) {
            Integer kr = kwRank.get(id);
            Integer sr = semRank.get(id);
            double score = 0;
            if (kr != null) score += 1.0 / (rrfK + kr);
            if (sr != null) score += 1.0 / (rrfK + sr);
            VectorIndex.Hit h = best.get(id);
            out.add(new Ranked(id, score, exact.contains(id), kr,
                    h == null ? null : h.score(), h == null ? null : h.colId()));
        }
        out.sort(Comparator.comparing((Ranked r) -> !r.exact())
                .thenComparing((a, b) -> {
                    if (!a.exact() || !b.exact()) return 0;
                    return Integer.compare(a.keywordRank() == null ? Integer.MAX_VALUE : a.keywordRank(),
                            b.keywordRank() == null ? Integer.MAX_VALUE : b.keywordRank());
                })
                .thenComparing(Ranked::score, Comparator.reverseOrder())
                .thenComparingLong(Ranked::tblId));
        return List.copyOf(out.subList(0, Math.min(limit, out.size())));
    }
}
