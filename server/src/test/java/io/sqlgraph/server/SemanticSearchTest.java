package io.sqlgraph.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.sqlgraph.core.spi.Embedder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** search_table 하이브리드 검색 (개념 사전으로 뜻을 흉내 내는 가짜 Embedder, 실제 모델·네트워크 없음) */
class SemanticSearchTest {

    static final Path HR = Path.of("../samples/hr-project").toAbsolutePath().normalize();
    static final Duration WAIT = Duration.ofSeconds(30);
    static final ObjectMapper om = new ObjectMapper();

    @TempDir
    Path home;
    @TempDir
    Path home2;

    final List<Workspace> opened = new ArrayList<>();

    /** 개념 사전: 문장에 들어 있는 낱말의 개념 축을 1 로 (같은 개념이면 표현이 달라도 가깝다) */
    static class ConceptEmbedder implements Embedder {
        static final String[][] CONCEPTS = {
                {"발령", "인사이동", "전보", "승진"},
                {"급여", "월급", "봉급", "payroll"},
                {"부서", "조직", "팀"},
                {"사원", "직원", "employee", "사번", "성명"},
                {"코드"},
        };
        final String id;

        ConceptEmbedder(String id) {
            this.id = id;
        }

        ConceptEmbedder() {
            this("fake-concept-6");
        }

        @Override public String modelId() { return id; }
        @Override public int dim() { return CONCEPTS.length + 1; }

        @Override
        public float[][] embed(List<String> texts, Kind kind) {
            float[][] out = new float[texts.size()][];
            for (int i = 0; i < out.length; i++) {
                String t = texts.get(i).toLowerCase();
                float[] v = new float[dim()];
                for (int c = 0; c < CONCEPTS.length; c++) {
                    for (String w : CONCEPTS[c]) {
                        if (t.contains(w)) {
                            v[c] = 1;
                            break;
                        }
                    }
                }
                v[CONCEPTS.length] = 0.01f;   // 개념이 하나도 없어도 0 벡터가 되지 않게
                out[i] = v;
            }
            return out;
        }
    }

    private Workspace indexed(Path h, EmbedderFactory f, Config.Semantic sem) throws Exception {
        SqlGraphHome sh = new SqlGraphHome(h);
        Workspace ws = new Workspace(sh, Path.of(".").toAbsolutePath(), s -> { }, f);
        opened.add(ws);
        ws.pin(Config.load(HR.resolve("sqlgraph.yml"), sh.indexFor(HR)).withSemantic(sem));
        new Tools(ws, om).indexProject(HR.toString(), null, false);
        assertTrue(ws.service(HR).awaitSemantic(WAIT));
        return ws;
    }

    private static Config.Semantic on() {
        return new Config.Semantic(true, null, null, 0);
    }

    private static String search(Workspace ws, String kw, String mode) throws Exception {
        return new Tools(ws, om).searchTable(ws.service(HR), kw, false, 20, mode);
    }

    /** 결과 표의 데이터 행 (머리·구분선 제외) */
    private static List<String> rows(String out) {
        return out.lines().filter(l -> l.startsWith("| ") && !l.startsWith("| 테이블 ")).toList();
    }

    @AfterEach
    void tearDown() throws Exception {
        for (Workspace ws : opened) ws.close();
    }

    @Test
    void synonymFoundOnlyBySemantic() throws Exception {
        Workspace ws = indexed(home, (s, log) -> new ConceptEmbedder(), on());
        assertTrue(ws.service(HR).semanticReady());

        assertTrue(search(ws, "인사이동", "keyword").contains("해당하는 테이블이 없습니다"));
        String out = search(ws, "인사이동", "auto");
        List<String> rows = rows(out);
        assertFalse(rows.isEmpty(), out);
        assertTrue(rows.get(0).contains("HR_APPNT"), out);
        assertTrue(out.contains("| 근거 |"), out);
        assertTrue(rows.get(0).contains("| 의미 1.000 (컬럼 "), out);
    }

    @Test
    void exactNameFirstAndKeywordPlusSemantic() throws Exception {
        Workspace ws = indexed(home, (s, log) -> new ConceptEmbedder(), on());
        List<String> rows = rows(search(ws, "HR_EMP", "auto"));
        assertTrue(rows.get(0).contains("HR_EMP") && rows.get(0).endsWith("| 이름 |"), rows.toString());

        List<String> dept = rows(search(ws, "부서", "auto"));
        assertTrue(dept.get(0).contains("HR_DEPT") && dept.get(0).contains("| 키워드+의미 "), dept.toString());
    }

    @Test
    void relativeThresholdDropsFarHits() throws Exception {
        // '부서': 부서 테이블 컬럼은 1.0, 사원·발령 테이블의 부서 컬럼은 다른 개념과 섞여 약 0.71
        Workspace tight = indexed(home, (s, log) -> new ConceptEmbedder(), on());
        String t = search(tight, "부서", "semantic");
        assertTrue(t.contains("HR_DEPT"), t);
        assertFalse(t.contains("HR_EMP"), t);

        Workspace loose = indexed(home2, (s, log) -> new ConceptEmbedder(),
                new Config.Semantic(true, null, null, 0, 0.5, 50));
        String l = search(loose, "부서", "semantic");
        assertTrue(l.contains("HR_DEPT") && l.contains("HR_EMP"), l);
    }

    @Test
    void keywordModeUnchangedAndOffIsKeyword() throws Exception {
        Workspace on = indexed(home, (s, log) -> new ConceptEmbedder(), on());
        AtomicInteger offCalls = new AtomicInteger();
        Workspace off = indexed(home2, (s, log) -> {
            offCalls.incrementAndGet();
            return new ConceptEmbedder();
        }, Config.Semantic.OFF);

        for (String kw : List.of("부서", "발령", "인사이동", "HR_EMP")) {
            String keyword = search(on, kw, "keyword");
            assertEquals(keyword, search(off, kw, "auto"), kw);
            assertEquals(keyword, new Tools(off, om).searchTable(off.service(HR), kw, false, 20), kw);
            assertFalse(keyword.contains("근거"), keyword);
        }
        assertTrue(search(off, "부서", "semantic").startsWith("의미 검색을 쓸 수 없습니다: 꺼짐"));
        assertEquals(0, offCalls.get());
        assertTrue(search(on, "부서", "fuzzy").startsWith("mode 는"));
    }

    @Test
    void queryEmbedderOpenedOnce() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        Workspace ws = indexed(home, (s, log) -> {
            calls.incrementAndGet();
            return new ConceptEmbedder();
        }, on());
        assertEquals(1, calls.get());   // 인덱싱 작업
        for (int i = 0; i < 5; i++) search(ws, i % 2 == 0 ? "인사이동" : "월급", "auto");
        assertEquals(2, calls.get());   // + 질의용 한 번
    }

    @Test
    void modelMismatchFallsBackToKeyword() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        Workspace ws = indexed(home, (s, log) ->
                new ConceptEmbedder(calls.incrementAndGet() == 1 ? "model-a" : "model-b"), on());
        assertFalse(ws.service(HR).semanticReady());
        assertEquals(search(ws, "부서", "keyword"), search(ws, "부서", "auto"));
        assertTrue(search(ws, "인사이동", "auto").contains("해당하는 테이블이 없습니다"));
    }

    @Test
    void reindexKeepsSearchWorking() throws Exception {
        Workspace ws = indexed(home, (s, log) -> new ConceptEmbedder(), on());
        String before = search(ws, "인사이동", "auto");
        new Tools(ws, om).indexProject(HR.toString(), null, false);
        assertTrue(ws.service(HR).awaitSemantic(WAIT));
        assertEquals(before, search(ws, "인사이동", "auto"));
    }

    @Test
    void evalOnSampleSet() throws Exception {
        Workspace ws = indexed(home, (s, log) -> new ConceptEmbedder(), on());
        List<SemanticEval.Query> qs = SemanticEval.load(HR.resolve("semantic-eval.yml"));
        assertTrue(qs.size() >= 20, "질의 수: " + qs.size());
        SqlGraphService svc = ws.service(HR);
        for (SemanticEval.Query q : qs) {
            for (String t : q.expect()) {
                assertFalse(svc.store().findTables(t, null).isEmpty(), "평가셋의 정답 테이블이 인덱스에 없음: " + t);
            }
        }
        String report = SemanticEval.report(SemanticEval.run(svc, qs), 5);
        assertTrue(report.contains("| keyword |") && report.contains("| auto |"), report);
    }
}
