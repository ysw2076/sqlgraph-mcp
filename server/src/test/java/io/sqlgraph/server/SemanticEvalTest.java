package io.sqlgraph.server;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 평가 계산 (순수 함수) */
class SemanticEvalTest {

    @TempDir
    Path dir;

    @Test
    void recallAndMrrByHand() {
        // 1위, 3위, 못 찾음 → Recall@1 = 1/3, Recall@5 = 2/3, MRR = (1 + 1/3 + 0) / 3
        List<Integer> ranks = Arrays.asList(1, 3, null);
        assertEquals(1 / 3.0, SemanticEval.score(ranks, 1).recall(), 1e-9);
        assertEquals(2 / 3.0, SemanticEval.score(ranks, 5).recall(), 1e-9);
        assertEquals((1 + 1 / 3.0) / 3, SemanticEval.score(ranks, 5).mrr(), 1e-9);
        assertEquals(0, SemanticEval.score(List.of(), 5).recall());
    }

    @Test
    void firstRankIgnoresCaseAndTakesAnyExpected() {
        assertEquals(2, SemanticEval.firstRank(List.of("HR_DEPT", "hr_emp", "PAY_MONTH"), List.of("HR_EMP")));
        assertEquals(2, SemanticEval.firstRank(List.of("A", "B", "C"), List.of("C", "B")));
        assertNull(SemanticEval.firstRank(List.of("A"), List.of("Z")));
    }

    @Test
    void loadsYaml() throws Exception {
        Path f = dir.resolve("e.yml");
        Files.writeString(f, """
                queries:
                  - { q: "월급", expect: [PAY_MONTH], kind: synonym }
                  - { q: "부서", expect: HR_DEPT }
                """);
        List<SemanticEval.Query> qs = SemanticEval.load(f);
        assertEquals(2, qs.size());
        assertEquals(List.of("PAY_MONTH"), qs.get(0).expect());
        assertEquals("synonym", qs.get(0).kind());
        assertEquals(List.of("HR_DEPT"), qs.get(1).expect());
        assertNull(qs.get(1).kind());

        Files.writeString(f, "foo: 1\n");
        assertThrows(IllegalArgumentException.class, () -> SemanticEval.load(f));
    }

    @Test
    void percentile() {
        long[] ms = {1, 2, 3, 4, 100};
        assertEquals(3, SemanticEval.pct(ms, 50));
        assertEquals(100, SemanticEval.pct(ms, 95));
    }
}
