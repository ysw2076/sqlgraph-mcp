package io.sqlgraph.core;

/**
 * SQL 주석 처리. 문자열 리터럴('..', "..", `..`) 안의 -- 와 /* 는 건드리지 않는다.
 *
 * <p>추출기는 SQL 을 한 줄로 합쳐서 저장하는데, 그대로 합치면 {@code -- 주석} 뒤의 SQL 전체가 주석이 된다.
 * 그래서 합치기 전에 {@link #lineToBlock} 으로 줄 주석을 블록 주석으로 바꾸고, 분석 직전에 {@link #strip} 으로 지운다.
 */
public final class SqlComments {

    private SqlComments() {}

    /** iBatis 바인드 #name# / #name:VARCHAR# */
    private static final java.util.regex.Pattern IBATIS_BIND =
            java.util.regex.Pattern.compile("#[A-Za-z_][\\w.\\[\\]]*(?::\\w+)*#");

    /**
     * MySQL {@code # 주석} 인지: 문자열 밖이고, 앞이 공백·줄 시작·콤마·괄호이며, {@code #{..}} 나 iBatis {@code #name#} 이 아님.
     * (Oracle 식별자 안의 # 은 앞이 글자이므로 제외된다)
     */
    private static boolean hashComment(String sql, int i) {
        if (sql.charAt(i) != '#') return false;
        if (i + 1 < sql.length() && sql.charAt(i + 1) == '{') return false;
        if (i > 0) {
            char p = sql.charAt(i - 1);
            if (!Character.isWhitespace(p) && p != ',' && p != '(' && p != ')') return false;
        }
        java.util.regex.Matcher m = IBATIS_BIND.matcher(sql);
        return !(m.find(i) && m.start() == i);
    }

    /** {@code -- 내용} / MySQL {@code # 내용} → {@code /* 내용 *}{@code /} (줄 단위 의미를 유지한 채 한 줄로 합칠 수 있게) */
    public static String lineToBlock(String sql) {
        if (sql == null || (!sql.contains("--") && !sql.contains("#"))) return sql;
        StringBuilder sb = new StringBuilder(sql.length() + 16);
        int n = sql.length();
        for (int i = 0; i < n; i++) {
            char c = sql.charAt(i);
            if (c == '\'' || c == '"' || c == '`') {
                int e = skipQuoted(sql, i);
                sb.append(sql, i, e);
                i = e - 1;
                continue;
            }
            if (c == '/' && i + 1 < n && sql.charAt(i + 1) == '*') {
                int e = sql.indexOf("*/", i + 2);
                int stop = e < 0 ? n : e + 2;
                sb.append(sql, i, stop);
                i = stop - 1;
                continue;
            }
            boolean dash = c == '-' && i + 1 < n && sql.charAt(i + 1) == '-';
            if (dash || hashComment(sql, i)) {
                int e = sql.indexOf('\n', i);
                int stop = e < 0 ? n : e;
                String text = sql.substring(i + (dash ? 2 : 1), stop).replace("*/", "* /").strip();
                sb.append("/* ").append(text).append(" */");
                i = stop - 1;
                continue;
            }
            sb.append(c);
        }
        return sb.toString();
    }

    /** 주석을 모두 공백 하나로 바꾼다 */
    public static String strip(String sql) {
        if (sql == null || (!sql.contains("--") && !sql.contains("/*"))) return sql;
        StringBuilder sb = new StringBuilder(sql.length());
        int n = sql.length();
        for (int i = 0; i < n; i++) {
            char c = sql.charAt(i);
            if (c == '\'' || c == '"' || c == '`') {
                int e = skipQuoted(sql, i);
                sb.append(sql, i, e);
                i = e - 1;
                continue;
            }
            if (c == '/' && i + 1 < n && sql.charAt(i + 1) == '*') {
                int e = sql.indexOf("*/", i + 2);
                i = (e < 0 ? n : e + 2) - 1;
                sb.append(' ');
                continue;
            }
            if (c == '-' && i + 1 < n && sql.charAt(i + 1) == '-') {
                int e = sql.indexOf('\n', i);
                i = (e < 0 ? n : e) - 1;
                sb.append(' ');
                continue;
            }
            sb.append(c);
        }
        return sb.toString();
    }

    /** 여는 따옴표 위치 i 부터 닫는 따옴표 다음 위치. '' 같은 이중 따옴표 이스케이프 처리 */
    private static int skipQuoted(String s, int i) {
        char q = s.charAt(i);
        int n = s.length();
        int k = i + 1;
        while (k < n) {
            char c = s.charAt(k);
            if (c == '\\' && q != '`' && k + 1 < n) {
                k += 2;
                continue;
            }
            if (c == q) {
                if (k + 1 < n && s.charAt(k + 1) == q) {
                    k += 2;
                    continue;
                }
                return k + 1;
            }
            k++;
        }
        return n;
    }
}
