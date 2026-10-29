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
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 샘플 프로젝트 전체 인덱싱 + MCP stdio 프로토콜 왕복 테스트 */
class EndToEndTest {

    static Path tmp;
    static SqlGraphService svc;
    static Tools tools;
    static final ObjectMapper om = new ObjectMapper();

    @BeforeAll
    static void setUp() throws Exception {
        tmp = Files.createTempDirectory("sqlgraph-test");
        Config base = Config.load(Path.of("../samples/hr-project/sqlgraph.yml"));
        Config cfg = new Config(base.file(), base.baseDir(), base.project(), tmp.resolve("graph.db"),
                base.databases(), base.include(), base.exclude(), base.maxFileBytes());
        svc = new SqlGraphService(cfg, s -> { });
        Indexer.Report r = svc.index();
        assertEquals(2, r.databases());
        assertEquals(23, r.statements(), "샘플의 쿼리 수 (build/ 폴더 제외)");
        assertEquals(0, r.failed(), "샘플 쿼리는 모두 파싱되어야 함");
        tools = new Tools(svc, om);
    }

    @AfterAll
    static void tearDown() throws Exception {
        svc.close();
        try (var s = Files.walk(tmp)) {
            s.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        }
    }

    @Test
    void searchByKoreanComment() throws Exception {
        String out = tools.call("search_table", om.readTree("{\"keyword\":\"발령\"}"));
        assertTrue(out.contains("HR_APPNT"));
        assertFalse(out.contains("HR_EMP"));
    }

    @Test
    void describeShowsColumnsAndQueries() throws Exception {
        String out = tools.call("describe_table", om.readTree("{\"table\":\"pay_month\"}"));
        assertTrue(out.contains("NET_PAY_AMT"));
        assertTrue(out.contains("실지급액"));
        assertTrue(out.contains("PayMapper.mergePayMonth"));
    }

    @Test
    void columnImpactSeparatesSureAndPossible() throws Exception {
        String out = tools.call("impact_analysis", om.readTree("{\"table\":\"HR_EMP\",\"column\":\"DEPT_CD\"}"));
        String sure = out.substring(out.indexOf("### 확실히 사용"), out.indexOf("### 사용 가능성"));
        String maybe = out.substring(out.indexOf("### 사용 가능성"));
        assertTrue(sure.contains("AppntMapper.applyAppnt"), "다중 테이블 UPDATE 의 SET e.DEPT_CD");
        assertTrue(sure.contains("AppntMapper.insertAppnt"), "INSERT ... SELECT DEPT_CD FROM HR_EMP");
        assertTrue(sure.contains("LegacyEmp.getEmpList"), "iBatis");
        assertTrue(maybe.contains("EmpMapper.selectEmp "), "SELECT * 는 가능성");
        assertFalse(out.contains("retireEmp"), "DEPT_CD 를 쓰지 않는 쿼리");
    }

    @Test
    void findQueryByMapperId() throws Exception {
        String out = tools.call("find_query", om.readTree("{\"keyword\":\"mergePayMonth\"}"));
        assertTrue(out.contains("MERGE INTO PAY_MONTH"));
        assertTrue(out.contains("PAY_MONTH(W)"));
        assertTrue(out.contains("PAY_ITEM(R)"));
    }

    @Test
    void mcpProtocolRoundTrip() throws Exception {
        String input = String.join("\n",
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-06-18\",\"capabilities\":{},\"clientInfo\":{\"name\":\"test\",\"version\":\"1\"}}}",
                "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}",
                "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}",
                "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\",\"params\":{\"name\":\"search_table\",\"arguments\":{\"keyword\":\"급여\"}}}",
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
        assertEquals(6, res.get(1).at("/result/tools").size());
        assertTrue(res.get(2).at("/result/content/0/text").asText().contains("PAY_MONTH"));
        assertFalse(res.get(2).at("/result/isError").asBoolean());
        assertTrue(res.get(3).at("/result/isError").asBoolean(), "필수 인자 누락은 도구 오류");
        assertEquals(-32601, res.get(4).at("/error/code").asInt());
    }
}
