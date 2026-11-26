package io.sqlgraph.core.semantic;

import io.sqlgraph.core.store.GraphStore;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 저장소의 테이블·컬럼에서 임베딩할 문장을 만든다. */
public final class DocBuilder {

    /** 테이블 문서에 넣는 컬럼 코멘트 최대 수 */
    public static final int MAX_TABLE_COLUMNS = 30;
    /** 코멘트 없는 테이블 문서에 넣는 owner 최대 수 */
    public static final int MAX_OWNERS = 10;

    /**
     * 임베딩 문서 한 건.
     *
     * @param kind  "t" (테이블) 또는 "c" (컬럼)
     * @param colId 컬럼일 때만, 테이블이면 null
     */
    public record Doc(String kind, long tblId, Long colId, String text) {}

    private DocBuilder() {}

    /** 테이블 id 순으로 테이블 문서, 이어서 그 테이블의 컬럼 문서 (ordinal 순) */
    public static List<Doc> build(GraphStore store) throws SQLException {
        Map<Long, List<GraphStore.DocColumn>> colsByTable = new LinkedHashMap<>();
        for (GraphStore.DocColumn c : store.commentedColumns()) {
            colsByTable.computeIfAbsent(c.tblId(), k -> new ArrayList<>()).add(c);
        }
        List<Doc> docs = new ArrayList<>();
        for (GraphStore.DocTable t : store.docTables()) {
            List<GraphStore.DocColumn> cols = colsByTable.getOrDefault(t.id(), List.of());
            List<String> owners = isBlank(t.comment()) ? store.tableOwners(t.id(), MAX_OWNERS) : List.of();
            docs.add(new Doc("t", t.id(), null, tableText(t.name(), t.comment(), cols, owners)));
            String prefix = isBlank(t.comment()) ? t.name() : t.comment().strip();
            for (GraphStore.DocColumn c : cols) {
                docs.add(new Doc("c", t.id(), c.id(),
                        prefix + " > " + c.comment().strip() + " (" + splitName(c.name()) + ")"));
            }
        }
        return docs;
    }

    static String tableText(String name, String comment, List<GraphStore.DocColumn> cols, List<String> owners) {
        StringBuilder sb = new StringBuilder();
        if (!isBlank(comment)) sb.append(comment.strip()).append(". ");
        sb.append("테이블 ").append(name).append('.');
        List<String> cc = new ArrayList<>();
        for (GraphStore.DocColumn c : cols) {
            if (isBlank(c.comment())) continue;
            cc.add(c.comment().strip());
            if (cc.size() >= MAX_TABLE_COLUMNS) break;
        }
        if (!cc.isEmpty()) sb.append(" 컬럼: ").append(String.join(", ", cc));
        if (isBlank(comment) && !owners.isEmpty()) sb.append(" 사용 쿼리: ").append(String.join(", ", owners));
        return sb.toString();
    }

    /** 이름 분해: APPNT_DT → APPNT DT, empNo → emp No, EMP → EMP */
    public static String splitName(String name) {
        if (name == null) return "";
        String s = name.replaceAll("[_\\s]+", " ");
        s = s.replaceAll("(?<=[a-z])(?=[A-Z])", " ");
        return s.strip().replaceAll("\\s+", " ");
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
