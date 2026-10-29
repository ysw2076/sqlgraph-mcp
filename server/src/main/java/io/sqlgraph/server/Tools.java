package io.sqlgraph.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.sqlgraph.core.index.Indexer;
import io.sqlgraph.core.store.GraphStore;
import io.sqlgraph.core.store.GraphStore.ColumnInfo;
import io.sqlgraph.core.store.GraphStore.StmtDetail;
import io.sqlgraph.core.store.GraphStore.StmtHit;
import io.sqlgraph.core.store.GraphStore.TableHit;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** MCP 도구 정의와 실행. 결과는 토큰을 아끼도록 간결한 마크다운 텍스트로 돌려준다. */
public final class Tools {

    private final Workspace ws;
    private final ObjectMapper om;

    public Tools(Workspace ws, ObjectMapper om) {
        this.ws = ws;
        this.om = om;
    }

    // ============================================================== definitions

    public ArrayNode definitions() {
        ArrayNode tools = om.createArrayNode();
        tools.add(tool("index_project",
                "프로젝트 폴더를 인덱싱합니다 (처음이면 등록). 사용자가 'OO 프로젝트 DB 인덱싱해줘'라고 하면 그 폴더의 "
                        + "절대 경로를 path 로 주세요. DB 접속 정보는 서버가 application.yml, .env 등에서 직접 찾으므로 "
                        + "비밀번호를 읽어서 넘기지 마세요. Mapper XML 이나 DB 스키마가 바뀐 뒤에도 다시 호출합니다.",
                props(p -> {
                    p.set("path", prop("string", "프로젝트 폴더 절대 경로. 생략하면 현재 프로젝트(또는 Claude 를 연 폴더)"));
                    p.set("profile", prop("string", "Spring 프로필 (기본: local → dev → 기본 설정 순으로 자동 선택)"));
                    p.set("skip_db", prop("boolean", "DB 접속 없이 소스의 쿼리만 인덱싱 (기본 false)"));
                }), null));
        tools.add(tool("list_projects", "인덱싱해 둔 프로젝트 목록과 현재 프로젝트를 보여줍니다.",
                props(p -> { }), null));
        tools.add(tool("search_table",
                "DB 테이블을 이름 또는 한글 코멘트로 검색합니다 (예: '발령', 'EMP', '사원 부서'). "
                        + "어떤 테이블이 있는지 모를 때 grep 대신 먼저 사용하세요. 공백으로 나눈 키워드는 모두 포함되어야 합니다.",
                props(p -> {
                    p.set("keyword", prop("string", "테이블명 또는 코멘트 키워드"));
                    p.set("project", PROJECT_PROP);
                    p.set("include_columns", prop("boolean", "컬럼명/컬럼 코멘트까지 검색 (기본 false)"));
                    p.set("limit", prop("integer", "최대 결과 수 (기본 20)"));
                }), "keyword"));
        tools.add(tool("describe_table",
                "테이블의 컬럼(타입, PK, NULL 여부, 코멘트), FK, 그리고 이 테이블을 사용하는 쿼리 목록"
                        + "(MyBatis Mapper id, 파일:줄, 읽기/쓰기)을 보여줍니다.",
                props(p -> {
                    p.set("table", prop("string", "테이블명 (SCHEMA.TABLE 형식도 가능)"));
                    p.set("project", PROJECT_PROP);
                    p.set("db", prop("string", "여러 DB 에 같은 이름이 있을 때 db id"));
                    p.set("query_limit", prop("integer", "보여줄 쿼리 수 (기본 30)"));
                }), "table"));
        tools.add(tool("impact_analysis",
                "테이블 또는 컬럼을 변경/삭제할 때 영향받는 쿼리, Mapper 메서드, 파일 위치를 읽기/쓰기로 나눠 보여줍니다. "
                        + "컬럼을 주면 '확실히 사용'과 '사용 가능성 있음(SELECT *, 테이블 미지정, 동적 SQL, 파싱 실패)'으로 구분합니다.",
                props(p -> {
                    p.set("table", prop("string", "테이블명"));
                    p.set("project", PROJECT_PROP);
                    p.set("column", prop("string", "컬럼명 (생략하면 테이블 단위)"));
                    p.set("limit", prop("integer", "구간별 최대 결과 수 (기본 50)"));
                }), "table"));
        tools.add(tool("find_query",
                "Mapper 메서드명(namespace.id 또는 id 일부)이나 SQL 본문 키워드로 쿼리를 찾아 실제 SQL 과 사용 테이블을 보여줍니다.",
                props(p -> {
                    p.set("keyword", prop("string", "예: EmpMapper.selectEmpList, selectEmp, HR_APPNT"));
                    p.set("project", PROJECT_PROP);
                    p.set("limit", prop("integer", "최대 결과 수 (기본 10)"));
                    p.set("full_sql", prop("boolean", "SQL 전체 표시 (기본 false: 600자까지)"));
                }), "keyword"));
        tools.add(tool("index_status",
                "인덱스 상태: 마지막 인덱싱 시각, 연결한 DB, 테이블/쿼리 수, 파싱 실패 샘플, DB 메타데이터에 없는 테이블 목록.",
                props(p -> p.set("project", PROJECT_PROP)), null));
        return tools;
    }

    private final ObjectNode PROJECT_PROP = projectProp();

    private ObjectNode projectProp() {
        ObjectNode p = new ObjectMapper().createObjectNode();
        p.put("type", "string");
        p.put("description", "프로젝트 이름(list_projects) 또는 폴더 경로. 생략하면 현재 프로젝트");
        return p;
    }

    private ObjectNode tool(String name, String desc, ObjectNode properties, String required) {
        ObjectNode t = om.createObjectNode();
        t.put("name", name);
        t.put("description", desc);
        ObjectNode schema = t.putObject("inputSchema");
        schema.put("type", "object");
        schema.set("properties", properties);
        if (required != null) schema.putArray("required").add(required);
        return t;
    }

    private ObjectNode props(java.util.function.Consumer<ObjectNode> f) {
        ObjectNode p = om.createObjectNode();
        f.accept(p);
        return p;
    }

    private ObjectNode prop(String type, String desc) {
        ObjectNode p = om.createObjectNode();
        p.put("type", type);
        p.put("description", desc);
        return p;
    }

    // ================================================================== dispatch

    public String call(String name, JsonNode args) throws Exception {
        JsonNode a = args == null ? om.createObjectNode() : args;
        return switch (name) {
            case "index_project" -> indexProject(opt(a, "path"), opt(a, "profile"), a.path("skip_db").asBoolean(false));
            case "list_projects" -> listProjects();
            case "search_table" -> searchTable(svc(a), req(a, "keyword"), a.path("include_columns").asBoolean(false),
                    a.path("limit").asInt(20));
            case "describe_table" -> describeTable(svc(a), req(a, "table"), opt(a, "db"), a.path("query_limit").asInt(30));
            case "impact_analysis" -> impact(svc(a), req(a, "table"), opt(a, "column"), a.path("limit").asInt(50));
            case "find_query" -> findQuery(svc(a), req(a, "keyword"), a.path("limit").asInt(10),
                    a.path("full_sql").asBoolean(false));
            case "index_status" -> status(svc(a));
            default -> throw new IllegalArgumentException("알 수 없는 도구: " + name);
        };
    }

    private SqlGraphService svc(JsonNode a) throws Exception {
        return ws.service(ws.resolve(opt(a, "project")));
    }

    private static String req(JsonNode a, String k) {
        String v = opt(a, k);
        if (v == null) throw new IllegalArgumentException("'" + k + "' 값이 필요합니다");
        return v;
    }

    private static String opt(JsonNode a, String k) {
        JsonNode n = a.get(k);
        if (n == null || n.isNull()) return null;
        String v = n.asText().strip();
        return v.isEmpty() ? null : v;
    }

    // ================================================================= handlers

    String searchTable(SqlGraphService svc, String keyword, boolean includeColumns, int limit) throws Exception {
        List<TableHit> hits = svc.store().searchTables(keyword, includeColumns, clamp(limit, 1, 200));
        if (hits.isEmpty()) {
            return "'" + keyword + "' 에 해당하는 테이블이 없습니다."
                    + (includeColumns ? "" : " include_columns=true 로 컬럼까지 검색해 보세요.");
        }
        StringBuilder sb = new StringBuilder();
        sb.append("테이블 ").append(hits.size()).append("건\n\n");
        sb.append("| 테이블 | DB | 코멘트 | 컬럼 | 사용 쿼리 |\n|---|---|---|---|---|\n");
        for (TableHit h : hits) {
            sb.append("| ").append(qualified(h)).append(h.kind().equals("VIEW") ? " (VIEW)" : "")
                    .append(" | ").append(h.db())
                    .append(" | ").append(nz(h.comment()))
                    .append(" | ").append(h.columnCount())
                    .append(" | ").append(h.stmtCount()).append(" |\n");
        }
        return sb.toString();
    }

    String describeTable(SqlGraphService svc, String table, String db, int queryLimit) throws Exception {
        GraphStore st = svc.store();
        List<TableHit> found = st.findTables(table, db);
        StringBuilder sb = new StringBuilder();
        if (found.isEmpty()) {
            int used = st.countStmtsForTable(table);
            if (used == 0) {
                List<TableHit> similar = st.searchTables(io.sqlgraph.core.Names.lastPart(table), false, 5);
                sb.append("테이블 '").append(table).append("' 을 찾지 못했습니다.");
                if (!similar.isEmpty()) {
                    sb.append(" 비슷한 이름: ");
                    sb.append(String.join(", ", similar.stream().map(TableHit::name).toList()));
                }
                return sb.toString();
            }
            sb.append("## ").append(table).append("\n\nDB 메타데이터에는 없지만 쿼리에서 사용됩니다 ")
                    .append("(다른 DB, 동의어/뷰, 오타 가능성).\n\n");
            appendStmts(sb, st.stmtsForTable(table, queryLimit), used, st.accessCounts(table));
            return sb.toString();
        }
        for (TableHit h : found) {
            sb.append("## ").append(qualified(h)).append("  (db: ").append(h.db()).append(", ").append(h.kind())
                    .append(")");
            if (h.comment() != null) sb.append(" — ").append(h.comment());
            sb.append("\n\n");
            List<ColumnInfo> cols = st.columns(h.id());
            sb.append("| 컬럼 | 타입 | PK | NULL | 코멘트 |\n|---|---|---|---|---|\n");
            for (ColumnInfo c : cols) {
                sb.append("| ").append(c.name())
                        .append(" | ").append(nz(c.type())).append(c.size() != null ? "(" + c.size() + ")" : "")
                        .append(" | ").append(c.pk() ? "PK" : "")
                        .append(" | ").append(c.nullable() ? "Y" : "N")
                        .append(" | ").append(nz(c.comment())).append(" |\n");
            }
            var fks = st.foreignKeys(h.id());
            if (!fks.isEmpty()) {
                sb.append("\nFK: ");
                sb.append(String.join(", ", fks.stream()
                        .map(f -> f.column() + " → " + f.refTable() + "." + nz(f.refColumn())).toList()));
                sb.append('\n');
            }
            var refBy = st.referencedBy(h.name());
            if (!refBy.isEmpty()) sb.append("참조됨: ").append(String.join(", ", refBy)).append('\n');
            sb.append('\n');
        }
        int total = st.countStmtsForTable(table);
        appendStmts(sb, st.stmtsForTable(table, queryLimit), total, st.accessCounts(table));
        return sb.toString();
    }

    String impact(SqlGraphService svc, String table, String column, int limit) throws Exception {
        GraphStore st = svc.store();
        int lim = clamp(limit, 1, 500);
        StringBuilder sb = new StringBuilder();
        List<TableHit> found = st.findTables(table, null);
        if (column == null) {
            sb.append("## 영향도: 테이블 ").append(table).append("\n\n");
            if (found.isEmpty()) sb.append("(DB 메타데이터에 없는 테이블)\n\n");
            List<StmtHit> all = st.stmtsForTable(table, lim);
            int total = st.countStmtsForTable(table);
            if (total == 0) return sb.append("이 테이블을 사용하는 쿼리가 없습니다.").toString();
            int[] ac = st.accessCounts(table);
            sb.append("### 사용 쿼리 ").append(total).append("건 (쓰기 ").append(ac[0]).append(" / 읽기 ").append(ac[1]).append(")\n");
            appendGrouped(sb, all);
            if (total > all.size()) sb.append("\n… 외 ").append(total - all.size()).append("건 (limit 조정)\n");
            for (TableHit h : found) {
                var refBy = st.referencedBy(h.name());
                if (!refBy.isEmpty()) sb.append("\nFK 로 참조하는 테이블: ").append(String.join(", ", refBy)).append('\n');
            }
            appendSummary(sb, all);
            return sb.toString();
        }

        sb.append("## 영향도: ").append(table).append('.').append(column).append("\n\n");
        boolean columnKnown = false;
        for (TableHit h : found) {
            for (ColumnInfo c : st.columns(h.id())) {
                if (c.name().equalsIgnoreCase(column)) {
                    columnKnown = true;
                    sb.append("컬럼 정보: ").append(nz(c.type())).append(c.size() != null ? "(" + c.size() + ")" : "")
                            .append(c.pk() ? ", PK" : "").append(c.comment() != null ? ", " + c.comment() : "")
                            .append(" (db: ").append(h.db()).append(")\n\n");
                }
            }
        }
        if (!found.isEmpty() && !columnKnown) sb.append("※ DB 메타데이터에 이 컬럼이 없습니다.\n\n");

        List<StmtHit> sure = st.stmtsForColumn(table, column, lim);
        List<StmtHit> maybe = st.possibleStmtsForColumn(table, column, lim);
        sb.append("### 확실히 사용: ").append(sure.size()).append("건").append(countByAccess(sure)).append('\n');
        appendGrouped(sb, sure);
        sb.append("\n### 사용 가능성 있음: ").append(maybe.size()).append("건\n");
        if (maybe.isEmpty()) {
            sb.append("(없음)\n");
        } else {
            for (StmtHit h : maybe) sb.append(line(h)).append(" — ").append(reason(h)).append('\n');
        }
        List<StmtHit> both = new ArrayList<>(sure);
        both.addAll(maybe);
        appendSummary(sb, both);
        return sb.toString();
    }

    String findQuery(SqlGraphService svc, String keyword, int limit, boolean fullSql) throws Exception {
        List<StmtDetail> list = svc.store().findQueries(keyword, clamp(limit, 1, 100));
        if (list.isEmpty()) return "'" + keyword + "' 에 해당하는 쿼리가 없습니다.";
        StringBuilder sb = new StringBuilder();
        for (StmtDetail d : list) {
            sb.append("### ").append(d.owner()).append("  [").append(d.kind()).append("]\n");
            sb.append(d.file()).append(d.line() > 0 ? ":" + d.line() : "");
            if (!d.tables().isEmpty()) sb.append("  테이블: ").append(String.join(", ", d.tables()));
            if (d.dynamic()) sb.append("  (동적 SQL)");
            if (!d.parsed()) sb.append("  (파싱 실패: ").append(nz(d.error())).append(")");
            sb.append("\n```sql\n");
            String sql = d.sql();
            if (!fullSql && sql.length() > 600) sql = sql.substring(0, 600) + " …(생략, full_sql=true)";
            sb.append(sql).append("\n```\n\n");
        }
        return sb.toString();
    }

    String status(SqlGraphService svc) throws Exception {
        GraphStore st = svc.store();
        Map<String, String> meta = st.meta();
        Map<String, Integer> c = st.counts();
        StringBuilder sb = new StringBuilder("## sqlgraph 인덱스 상태\n\n");
        sb.append("- 마지막 인덱싱: ").append(meta.getOrDefault("indexed_at", "(없음 — index_project 실행 필요)")).append('\n');
        sb.append("- 프로젝트: ").append(meta.getOrDefault("project_root", String.valueOf(svc.config().project()))).append('\n');
        sb.append("- DB: ");
        List<Map.Entry<String, String>> dbs = st.databases();
        sb.append(dbs.isEmpty() ? "(연결 안 함 — 소스의 쿼리만 인덱싱)"
                : String.join(", ", dbs.stream().map(e -> e.getKey() + " (" + e.getValue() + ")").toList()));
        sb.append('\n');
        String dbSrc = meta.get("databases");
        if (dbSrc != null && !dbSrc.isBlank()) dbSrc.lines().forEach(l -> sb.append("  - ").append(l).append('\n'));
        String dn = meta.get("detect_notes");
        if (dn != null && !dn.isBlank()) dn.lines().forEach(l -> sb.append("  ※ ").append(l).append('\n'));
        sb.append("- 테이블 ").append(c.get("tables")).append(" / 컬럼 ").append(c.get("columns")).append('\n');
        sb.append("- 쿼리 ").append(c.get("statements")).append(" (파싱 실패 ").append(c.get("parseFailed"))
                .append(", 동적 ").append(c.get("dynamic")).append(")\n");
        List<String> unknown = st.unknownTables(15);
        if (!unknown.isEmpty()) {
            sb.append("- DB 메타데이터에 없는 테이블 (쿼리 수): ").append(String.join(", ", unknown)).append('\n');
        }
        String warn = meta.get("warnings");
        if (warn != null && !warn.isBlank()) sb.append("\n경고:\n").append(warn.lines().map(l -> "- " + l + "\n").reduce("", String::concat));
        List<StmtDetail> failed = st.failedSamples(5);
        if (!failed.isEmpty()) {
            sb.append("\n파싱 실패 샘플 (테이블은 정규식으로 추출됨):\n");
            for (StmtDetail d : failed) {
                sb.append("- ").append(d.owner()).append(" (").append(d.file()).append(":").append(d.line())
                        .append("): ").append(nz(d.error())).append('\n');
            }
        }
        return sb.toString();
    }

    String indexProject(String path, String profile, boolean skipDb) throws Exception {
        Path project;
        if (path != null) {
            project = ws.resolve(path);
        } else {
            try {
                project = ws.resolve(null);
            } catch (IllegalArgumentException e) {
                project = ws.cwd();   // 처음 쓰는 경우: Claude 를 연 폴더
            }
        }
        Workspace.Indexed r = ws.index(project, profile, skipDb);
        Indexer.Report rep = r.outcome().report();
        StringBuilder sb = new StringBuilder("## 인덱싱 완료: ").append(r.entry().name()).append("  (")
                .append(rep.millis()).append("ms)\n");
        sb.append("- 경로: ").append(project).append('\n');
        if (r.config().file() != null) sb.append("- 설정: ").append(r.config().file()).append('\n');
        if (r.outcome().databases().isEmpty()) {
            sb.append("- DB: 연결 안 함 (소스의 쿼리만 인덱싱)\n");
        } else {
            sb.append("- DB:\n");
            r.outcome().databases().forEach(d -> sb.append("  - ").append(d).append('\n'));
        }
        sb.append("- 테이블 ").append(rep.tables()).append(", 컬럼 ").append(rep.columns()).append('\n');
        sb.append("- 파일 ").append(rep.filesScanned()).append("개 스캔 (쿼리 있는 파일 ").append(rep.filesWithSql()).append(")\n");
        sb.append("- 쿼리 ").append(rep.statements()).append("건: 파싱 성공 ").append(rep.parsed())
                .append(", 실패 ").append(rep.failed()).append(", 동적 ").append(rep.dynamic()).append('\n');
        List<String> notes = new ArrayList<>(r.outcome().notes());
        rep.warnings().stream().limit(20).forEach(notes::add);
        if (!notes.isEmpty()) {
            sb.append("\n확인 필요:\n");
            notes.forEach(w -> sb.append("- ").append(w).append('\n'));
        }
        sb.append("\n이제 search_table, describe_table, impact_analysis, find_query 로 조회할 수 있습니다.");
        return sb.toString();
    }

    /** CLI index 명령용 (현재 설정 그대로) */
    String reindex() throws Exception {
        return indexProject(null, null, false);
    }

    String listProjects() {
        List<ProjectRegistry.Entry> all = ws.registry().list();
        if (all.isEmpty()) return "인덱싱한 프로젝트가 없습니다. index_project 에 폴더 경로를 주세요 (현재 폴더: " + ws.cwd() + ")";
        Path cur = ws.current();
        StringBuilder sb = new StringBuilder("| 프로젝트 | 경로 | 마지막 인덱싱 |\n|---|---|---|\n");
        for (ProjectRegistry.Entry e : all) {
            boolean isCur = cur != null && SqlGraphHome.key(cur).equals(SqlGraphHome.key(e.path()));
            sb.append("| ").append(e.name()).append(isCur ? " (현재)" : "").append(" | ").append(e.path())
                    .append(" | ").append(e.indexedAt() == null ? "" : e.indexedAt()).append(" |\n");
        }
        return sb.toString();
    }

    // ================================================================= format

    private void appendStmts(StringBuilder sb, List<StmtHit> hits, int total, int[] accessCounts) {
        if (total == 0) {
            sb.append("사용하는 쿼리: 없음\n");
            return;
        }
        sb.append("### 사용 쿼리 ").append(total).append("건 (쓰기 ").append(accessCounts[0])
                .append(" / 읽기 ").append(accessCounts[1]).append(")\n");
        appendGrouped(sb, hits);
        if (total > hits.size()) sb.append("… 외 ").append(total - hits.size()).append("건\n");
    }

    private void appendGrouped(StringBuilder sb, List<StmtHit> hits) {
        List<StmtHit> w = hits.stream().filter(h -> h.access().equals("WRITE")).toList();
        List<StmtHit> r = hits.stream().filter(h -> !h.access().equals("WRITE")).toList();
        if (!w.isEmpty()) {
            sb.append("쓰기:\n");
            w.forEach(h -> sb.append(line(h)).append('\n'));
        }
        if (!r.isEmpty()) {
            sb.append("읽기:\n");
            r.forEach(h -> sb.append(line(h)).append('\n'));
        }
    }

    private static String line(StmtHit h) {
        StringBuilder s = new StringBuilder("- ").append(h.kind()).append(' ').append(h.owner())
                .append("  (").append(h.file()).append(h.line() > 0 ? ":" + h.line() : "").append(')');
        if (h.dynamic()) s.append(" [동적]");
        if (!h.parsed()) s.append(" [파싱실패]");
        return s.toString();
    }

    private static String reason(StmtHit h) {
        List<String> r = new ArrayList<>();
        if (h.selectAll()) r.add("SELECT *");
        if (!h.parsed()) r.add("파싱 실패");
        if (h.dynamic()) r.add("동적 SQL");
        if (r.isEmpty()) r.add("테이블 미지정 동명 컬럼");
        return String.join(", ", r);
    }

    private static String countByAccess(List<StmtHit> hits) {
        long w = hits.stream().filter(h -> h.access().equals("WRITE")).count();
        return " (쓰기 " + w + " / 읽기 " + (hits.size() - w) + ")";
    }

    /** 파일별/namespace 별 집계 */
    private static void appendSummary(StringBuilder sb, List<StmtHit> hits) {
        if (hits.isEmpty()) return;
        Map<String, Integer> byFile = new TreeMap<>();
        Map<String, Integer> byNs = new LinkedHashMap<>();
        for (StmtHit h : hits) {
            byFile.merge(h.file(), 1, Integer::sum);
            String o = h.owner();
            int i = o.lastIndexOf('.');
            String ns = o.contains("#") ? o.substring(0, o.indexOf('#')) : i > 0 ? o.substring(0, i) : o;
            byNs.merge(ns, 1, Integer::sum);
        }
        sb.append("\n요약: 파일 ").append(byFile.size()).append("개 — ");
        sb.append(String.join(", ", byNs.entrySet().stream().map(e -> shortNs(e.getKey()) + " " + e.getValue()).toList()));
        sb.append('\n');
    }

    private static String shortNs(String ns) {
        int i = ns.lastIndexOf('.');
        return i > 0 && !ns.contains("/") ? ns.substring(i + 1) : ns;
    }

    private static String qualified(TableHit h) {
        return h.name();
    }

    private static String nz(String s) {
        return s == null ? "" : s.replace("|", "\\|").replace("\n", " ");
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
