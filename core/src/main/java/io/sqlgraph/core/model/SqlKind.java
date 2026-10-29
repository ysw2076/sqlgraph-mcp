package io.sqlgraph.core.model;

/** 쿼리 종류. */
public enum SqlKind {
    SELECT, INSERT, UPDATE, DELETE, MERGE, CALL, DDL, OTHER;

    /** SQL 앞부분 키워드로 종류를 추정한다 (파싱 실패 시 사용). */
    public static SqlKind guess(String sql) {
        if (sql == null) return OTHER;
        String s = sql.stripLeading().toUpperCase();
        while (s.startsWith("(")) s = s.substring(1).stripLeading();
        if (s.startsWith("SELECT") || s.startsWith("WITH")) return SELECT;
        if (s.startsWith("INSERT") || s.startsWith("REPLACE")) return INSERT;
        if (s.startsWith("UPDATE")) return UPDATE;
        if (s.startsWith("DELETE")) return DELETE;
        if (s.startsWith("MERGE") || s.startsWith("UPSERT")) return MERGE;
        if (s.startsWith("CALL") || s.startsWith("{CALL") || s.startsWith("{ CALL")
                || s.startsWith("EXEC") || s.startsWith("BEGIN")) return CALL;
        if (s.startsWith("CREATE") || s.startsWith("ALTER") || s.startsWith("DROP")
                || s.startsWith("TRUNCATE")) return DDL;
        return OTHER;
    }
}
