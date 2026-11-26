package io.sqlgraph.core.semantic;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HybridRankerTest {

    private static VectorIndex.Hit t(long tbl, float s) {
        return new VectorIndex.Hit("t", tbl, null, s);
    }

    private static VectorIndex.Hit c(long tbl, long col, float s) {
        return new VectorIndex.Hit("c", tbl, col, s);
    }

    private static List<Long> ids(List<HybridRanker.Ranked> r) {
        return r.stream().map(HybridRanker.Ranked::tblId).toList();
    }

    @Test
    void exactFirstThenBothSidesAboveOneSide() {
        // 키워드: 5, 7, 9   의미: 7, 3, 9
        List<HybridRanker.Ranked> r = HybridRanker.rank(
                List.of(5L, 7L, 9L), List.of(t(7, 0.9f), t(3, 0.85f), t(9, 0.8f)), Set.of(9L), 0.5f, 10);
        assertEquals(9L, r.get(0).tblId(), "이름 정확 일치 맨 위");
        assertTrue(r.get(0).exact());
        assertEquals(7L, r.get(1).tblId(), "양쪽 다 있는 테이블");
        // 5(키워드 1위) 와 3(의미 2위): 1/61 > 1/62
        assertEquals(List.of(9L, 7L, 5L, 3L), ids(r));

        HybridRanker.Ranked seven = r.get(1);
        assertEquals(2, seven.keywordRank());
        assertEquals(0.9f, seven.semanticScore(), 1e-6);
        assertEquals(1.0 / 62 + 1.0 / 61, seven.score(), 1e-12);
        HybridRanker.Ranked three = r.get(3);
        assertNull(three.keywordRank());
        assertEquals(1.0 / 62, three.score(), 1e-12);
        assertFalse(three.exact());
    }

    @Test
    void exactOrderedByKeywordRank() {
        List<HybridRanker.Ranked> r = HybridRanker.rank(
                List.of(4L, 2L, 8L), List.of(t(8, 0.99f)), Set.of(8L, 2L), 0.5f, 10);
        assertEquals(List.of(2L, 8L, 4L), ids(r));
    }

    @Test
    void dropsBelowThreshold() {
        List<HybridRanker.Ranked> r = HybridRanker.rank(
                List.of(), List.of(t(1, 0.8f), t(2, 0.74f), c(3, 30, 0.1f)), Set.of(), 0.75f, 10);
        assertEquals(List.of(1L), ids(r));
    }

    @Test
    void groupsColumnHitsAndKeepsBestColumn() {
        List<HybridRanker.Ranked> r = HybridRanker.rank(
                List.of(), List.of(c(1, 11, 0.9f), t(1, 0.8f), c(1, 12, 0.85f), t(2, 0.88f)), Set.of(), 0.5f, 10);
        assertEquals(List.of(1L, 2L), ids(r));
        assertEquals(11L, r.get(0).bestColId());
        assertEquals(0.9f, r.get(0).semanticScore(), 1e-6);
        assertEquals(1.0 / 61, r.get(0).score(), 1e-12, "테이블당 의미 순위 하나");
        assertNull(r.get(1).bestColId(), "테이블 Hit 이면 bestColId 없음");
    }

    @Test
    void appliesLimitAndTieByTblId() {
        // 키워드 1위 = 의미 1위 점수와 같다 → 동점은 tblId 오름차순
        List<HybridRanker.Ranked> r = HybridRanker.rank(
                List.of(9L, 6L), List.of(t(4, 0.9f), t(2, 0.8f)), Set.of(), 0.5f, 10);
        assertEquals(List.of(4L, 9L, 2L, 6L), ids(r));

        List<HybridRanker.Ranked> limited = HybridRanker.rank(
                List.of(9L, 6L), List.of(t(4, 0.9f), t(2, 0.8f)), Set.of(), 0.5f, 3);
        assertEquals(List.of(4L, 9L, 2L), ids(limited));
    }

    @Test
    void customRrfConstant() {
        List<HybridRanker.Ranked> r = HybridRanker.rank(List.of(1L), List.of(), Set.of(), 0.5f, 10, 5);
        assertEquals(1.0 / 11, r.get(0).score(), 1e-12);
    }
}
