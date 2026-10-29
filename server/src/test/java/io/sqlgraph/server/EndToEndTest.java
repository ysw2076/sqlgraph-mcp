package io.sqlgraph.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.sqlgraph.core.index.Indexer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 샘플 프로젝트 인덱싱 + 여러 프로젝트 + MCP stdio 프로토콜 왕복 테스트 */
class EndToEndTest {

    static Path home;
    static Workspace ws;
    static Tools tools;
    static final ObjectMapper om = new ObjectMapper();
    static final Path HR = Path.of("../samples/hr-project").toAbsolutePath().normalize();
    static final Path SPRING = Path.of("../samples/spring-hr").toAbsolutePath().normalize();

    @BeforeAll
    static void setUp() throws Exception {
        home = Files.createTempDirectory("sqlgraph-home");
        ws = new Workspace(new SqlGraphHome(home), Path.of(".").toAbsolutePath(), s -> { });
        tools = new Tools(ws, om);
        Workspace.Indexed r = ws.index(HR, null, false);
        Indexer.Report rep = r.outcome().report();
        assertEquals(2, rep.databases());
        assertEquals(23, rep.statements(), "샘플의 쿼리 수 (build/ 폴더 제외)");
        assertEquals(0, rep.failed(), "샘플 쿼리는 모두 파싱되어야 함");
        assertTrue(r.config().index().startsWith(home), "인덱스는 프로젝트 폴더 밖(~/.sqlgraph)에 저장");
        assertFalse(Files.exists(HR.resolve(".sqlgraph")), "프로젝트 폴더에 파일을 만들지 않음");
    }

    @AfterAll
    static void tearDown() throws Exception {
        ws.close();
        try (var s = Files.walk(home)) {
            s.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        }
    }

    @Test
    void searchByKoreanComment() throws Exception {
        String out = tools.call("search_table", om.readTree("{\"keyword\":\"발령\",\"project\":\"hr-project\"}"));
        assertTrue(out.contains("HR_APPNT"));
        assertFalse(out.contains("HR_EMP"));
    }

    @Test
    void describeShowsColumnsAndQueries() throws Exception {
        String out = tools.call("describe_table", om.readTree("{\"table\":\"pay_month\",\"project\":\"hr-project\"}"));
        assertTrue(out.contains("NET_PAY_AMT"));
        assertTrue(out.contains("실지급액"));
        assertTrue(out.contains("PayMapper.mergePayMonth"));
    }

    @Test
    void columnImpactSeparatesSureAndPossible() throws Exception {
        String out = tools.call("impact_analysis", om.readTree("{\"table\":\"HR_EMP\",\"column\":\"DEPT_CD\",\"project\":\"hr-project\"}"));
        String sure = out.substring(out.indexOf("### 확실히 사용"), out.indexOf("### 사용 가능성"));
        String maybe = out.substring(out.indexOf("### 사용 가능성"));
        assertTrue(sure.contains("AppntMapper.applyAppnt"), "다중 테이블 UPDATE 의 SET e.DEPT_CD");
        assertTrue(sure.contains("AppntMapper.insertAppnt"), "INSERT ... SELECT DEPT_CD FROM HR_EMP");
        assertTrue(sure.contains("LegacyEmp.getEmpList"), "iBatis");
        assertTrue(maybe.contains("EmpMapper.selectEmp "), "SELECT * 는 가능성");
        assertFalse(out.contains("retireEmp"), "DEPT_CD 를 쓰지 않는 쿼리");
    }

    @Test
    void relationsInferredFromJoinsWithoutFk() throws Exception {
        String d = tools.call("describe_table", om.readTree("{\"table\":\"HR_EMP\",\"project\":\"hr-project\"}"));
        assertTrue(d.contains("HR_EMP.POS_CD → CM_CODE.CD  [CM_CODE.GRP_CD='POS']"), d);
        assertTrue(d.contains("HR_EMP.DEPT_CD → HR_DEPT.DEPT_CD  (FK · 쿼리"), "FK 와 쿼리 근거 합침: " + d);
        assertFalse(d.contains("RETIRE_YN='N'"), "PK 가 아닌 상수 조건은 관계 조건이 아님");

        String p = tools.call("find_join_path", om.readTree("{\"from\":\"HR_APPNT\",\"to\":\"CM_CODE\",\"project\":\"hr-project\"}"));
        assertTrue(p.contains("JOIN CM_CODE cc ON cc.CD = ha.APPNT_TYPE_CD AND cc.GRP_CD = 'APPNT'"), p);

        String two = tools.call("find_join_path", om.readTree("{\"from\":\"HR_APPNT\",\"to\":\"HR_DEPT\",\"project\":\"hr-project\"}"));
        assertTrue(two.contains("(1단계)"), "HR_APPNT.AFT_DEPT_CD/BEF_DEPT_CD → HR_DEPT 직접 관계: " + two);

        String none = tools.call("find_join_path", om.readTree("{\"from\":\"HR_EMP\",\"to\":\"PAY_MONTH\",\"project\":\"hr-project\"}"));
        assertTrue(none.contains("잇는 관계가 없습니다"), none);
    }

    @Test
    void findQueryByMapperId() throws Exception {
        String out = tools.call("find_query", om.readTree("{\"keyword\":\"mergePayMonth\",\"project\":\"hr-project\"}"));
        assertTrue(out.contains("MERGE INTO PAY_MONTH"));
        assertTrue(out.contains("PAY_MONTH(W)"));
        assertTrue(out.contains("PAY_ITEM(R)"));
    }

    @Test
    void multipleProjectsByNameAndPath() throws Exception {
        // 두 번째 프로젝트: DB 없이 소스만 (자동 탐지된 MariaDB 에 접속하지 않도록 skip_db)
        String idx = tools.call("index_project", om.readTree("{\"path\":\"" + json(SPRING) + "\",\"skip_db\":true}"));
        assertTrue(idx.contains("인덱싱 완료: spring-hr"), idx);
        assertTrue(idx.contains("쿼리 13건"), idx);

        String list = tools.call("list_projects", om.createObjectNode());
        assertTrue(list.contains("hr-project") && list.contains("spring-hr (현재)"), list);

        // project 생략 → 현재 프로젝트(spring-hr), 이름으로 hr-project 지정
        String cur = tools.call("find_query", om.readTree("{\"keyword\":\"PayMapper\"}"));
        assertTrue(cur.contains("해당하는 쿼리가 없습니다"), "spring-hr 에는 PayMapper 가 없음");
        String other = tools.call("find_query", om.readTree("{\"keyword\":\"PayMapper\",\"project\":\"hr-project\"}"));
        assertTrue(other.contains("mergePayMonth"));

        assertThrows(IllegalArgumentException.class,
                () -> tools.call("search_table", om.readTree("{\"keyword\":\"x\",\"project\":\"nope-project\"}")));

        // 새 Workspace (서버 재시작) 에서도 등록 목록이 유지됨
        try (Workspace ws2 = new Workspace(new SqlGraphHome(home), Path.of("/"), s -> { })) {
            String s = new Tools(ws2, om).call("search_table", om.readTree("{\"keyword\":\"급여\",\"project\":\"hr-project\"}"));
            assertTrue(s.contains("PAY_MONTH"));
        }
    }

    @Test
    void autoDetectReportsSpringDatasource() throws Exception {
        var cfg = ws.configFor(SPRING);
        assertTrue(cfg.autoDetect());
        var r = new io.sqlgraph.connector.detect.DbConfigDetector().detect(SPRING, null);
        assertEquals(1, r.chosen().size());
        assertEquals("jdbc:mariadb://localhost:3306/hr", r.chosen().get(0).url());
        assertTrue(r.notes().isEmpty() || r.notes().stream().noneMatch(n -> n.contains("local")));
    }

    @Test
    void mcpProtocolRoundTrip() throws Exception {
        String input = String.join("\n",
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-06-18\",\"capabilities\":{},\"clientInfo\":{\"name\":\"test\",\"version\":\"1\"}}}",
                "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}",
                "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}",
                "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\",\"params\":{\"name\":\"search_table\",\"arguments\":{\"keyword\":\"급여\",\"project\":\"hr-project\"}}}",
                "{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"tools/call\",\"params\":{\"name\":\"describe_table\",\"arguments\":{}}}",
                "{\"jsonrpc\":\"2.0\",\"id\":5,\"method\":\"nope\"}",
                "");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        new McpServer(om, tools, "test", s -> { })
                .run(new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)), out);
        List<JsonNode> res = new ArrayList<>();
        for (String line : out.toString(StandardCharsets.UTF_8).split("\n")) res.add(om.readTree(line));

        assertEquals(5, res.size(), "알림에는 응답하지 않는다");
        assertEquals("2025-06-18", res.get(0).at("/result/protocolVersion").asText());
        assertEquals("sqlgraph-mcp", res.get(0).at("/result/serverInfo/name").asText());
        assertEquals(8, res.get(1).at("/result/tools").size());
        assertTrue(res.get(2).at("/result/content/0/text").asText().contains("PAY_MONTH"));
        assertFalse(res.get(2).at("/result/isError").asBoolean());
        assertTrue(res.get(3).at("/result/isError").asBoolean(), "필수 인자 누락은 도구 오류");
        assertEquals(-32601, res.get(4).at("/error/code").asInt());
    }

    private static String json(Path p) {
        return p.toString().replace("\\", "\\\\");
    }
}
