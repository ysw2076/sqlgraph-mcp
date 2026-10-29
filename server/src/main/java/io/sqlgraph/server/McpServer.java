package io.sqlgraph.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.function.Consumer;

/**
 * MCP 서버 (stdio 전송). 한 줄에 JSON-RPC 메시지 하나.
 * stdout 은 프로토콜 전용이고 로그는 stderr 로만 쓴다.
 */
public final class McpServer {

    public static final String NAME = "sqlgraph-mcp";

    /** 지원 프로토콜 버전 (최신순). 클라이언트가 요청한 버전을 지원하면 그대로, 아니면 최신으로 응답 */
    static final List<String> PROTOCOL_VERSIONS = List.of("2025-11-25", "2025-06-18", "2025-03-26", "2024-11-05");

    private static final String INSTRUCTIONS = """
            이 서버는 프로젝트의 DB 스키마(테이블·컬럼·코멘트)와 소스 속 SQL(MyBatis Mapper XML, .sql)을 \
            인덱싱해 둔 그래프입니다. 테이블/컬럼 구조, 특정 테이블을 쓰는 쿼리나 Mapper 위치, \
            컬럼 변경 영향도, 테이블 간 조인 방법(find_join_path) 같은 질문에는 grep 으로 파일을 뒤지기 전에 \
            이 도구들을 먼저 사용하세요. 테이블 관계는 FK 가 없어도 소스 쿼리의 조인 조건에서 찾아 둡니다. \
            사용자가 "OO 프로젝트 DB 인덱싱해줘"라고 하면 index_project 에 그 폴더의 절대 경로를 주세요. \
            DB 접속 정보는 서버가 application.yml, .env 등에서 직접 읽으므로 비밀번호를 대신 읽어서 넘기지 마세요. \
            여러 프로젝트를 쓸 때는 각 도구의 project 인자로 지정합니다 (list_projects 로 목록 확인). \
            Mapper XML 이나 스키마가 바뀌었으면 index_project 를 다시 호출하세요.""";

    private final ObjectMapper om;
    private final Tools tools;
    private final String version;
    private final Consumer<String> log;
    private PrintStream out;

    public McpServer(ObjectMapper om, Tools tools, String version, Consumer<String> log) {
        this.om = om;
        this.tools = tools;
        this.version = version;
        this.log = log;
    }

    public void run(InputStream in, OutputStream rawOut) throws Exception {
        this.out = new PrintStream(rawOut, false, StandardCharsets.UTF_8);
        BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        String line;
        while ((line = reader.readLine()) != null) {
            if (line.isBlank()) continue;
            JsonNode msg;
            try {
                msg = om.readTree(line);
            } catch (Exception e) {
                send(error(null, -32700, "Parse error"));
                continue;
            }
            if (msg.isArray()) {
                for (JsonNode m : msg) handle(m);
            } else {
                handle(msg);
            }
        }
        log.accept("stdin 종료 — 서버를 마칩니다");
    }

    private void handle(JsonNode msg) {
        JsonNode id = msg.get("id");
        String method = msg.path("method").asText(null);
        if (method == null) return;               // 클라이언트의 응답 메시지 — 무시
        boolean notification = id == null || id.isNull();
        try {
            switch (method) {
                case "initialize" -> send(result(id, initialize(msg.path("params"))));
                case "ping" -> send(result(id, om.createObjectNode()));
                case "tools/list" -> {
                    ObjectNode r = om.createObjectNode();
                    r.set("tools", tools.definitions());
                    send(result(id, r));
                }
                case "tools/call" -> send(result(id, callTool(msg.path("params"))));
                default -> {
                    if (!notification) send(error(id, -32601, "Method not found: " + method));
                }
            }
        } catch (Exception e) {
            log.accept("요청 처리 실패 (" + method + "): " + e);
            if (!notification) send(error(id, -32603, String.valueOf(e.getMessage())));
        }
    }

    private ObjectNode initialize(JsonNode params) {
        String requested = params.path("protocolVersion").asText("");
        String pv = PROTOCOL_VERSIONS.contains(requested) ? requested : PROTOCOL_VERSIONS.get(0);
        ObjectNode r = om.createObjectNode();
        r.put("protocolVersion", pv);
        r.putObject("capabilities").putObject("tools").put("listChanged", false);
        ObjectNode info = r.putObject("serverInfo");
        info.put("name", NAME);
        info.put("version", version);
        r.put("instructions", INSTRUCTIONS);
        String client = params.path("clientInfo").path("name").asText("?");
        log.accept("initialize: client=" + client + ", protocol=" + pv);
        return r;
    }

    private ObjectNode callTool(JsonNode params) {
        String name = params.path("name").asText("");
        ObjectNode r = om.createObjectNode();
        String text;
        boolean isError = false;
        long t0 = System.currentTimeMillis();
        try {
            text = tools.call(name, params.get("arguments"));
        } catch (IllegalArgumentException e) {
            text = e.getMessage();
            isError = true;
        } catch (Exception e) {
            text = "오류: " + e.getMessage();
            isError = true;
            log.accept("도구 실패 " + name + ": " + e);
        }
        log.accept("tools/call " + name + " (" + (System.currentTimeMillis() - t0) + "ms)");
        ObjectNode c = r.putArray("content").addObject();
        c.put("type", "text");
        c.put("text", text);
        r.put("isError", isError);
        return r;
    }

    private ObjectNode result(JsonNode id, JsonNode result) {
        ObjectNode n = om.createObjectNode();
        n.put("jsonrpc", "2.0");
        n.set("id", id);
        n.set("result", result);
        return n;
    }

    private ObjectNode error(JsonNode id, int code, String message) {
        ObjectNode n = om.createObjectNode();
        n.put("jsonrpc", "2.0");
        n.set("id", id);
        ObjectNode e = n.putObject("error");
        e.put("code", code);
        e.put("message", message);
        return n;
    }

    private synchronized void send(JsonNode n) {
        try {
            out.print(om.writeValueAsString(n));
            out.print('\n');
            out.flush();
        } catch (Exception e) {
            log.accept("응답 쓰기 실패: " + e);
        }
    }
}
