package io.sqlgraph.core;

import java.util.ArrayList;
import java.util.List;

/** SQL 스크립트를 문장 단위로 나눈다. 세미콜론과 Oracle 의 단독 '/' 줄이 구분자. 문자열·주석 안은 무시. */
public final class SqlScripts {

    /** @param line 문장이 시작하는 줄 번호 (1부터) */
    public record Chunk(String sql, int line) {}

    private SqlScripts() {}

    public static List<Chunk> split(String text) {
        List<Chunk> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        int n = text.length();
        int line = 1;
        int startLine = -1;
        for (int i = 0; i < n; i++) {
            char c = text.charAt(i);
            char next = i + 1 < n ? text.charAt(i + 1) : '\0';
            if (c == '-' && next == '-') {
                while (i < n && text.charAt(i) != '\n') i++;
                if (i < n) { line++; cur.append('\n'); }
                continue;
            }
            if (c == '/' && next == '*') {
                int end = text.indexOf("*/", i + 2);
                int stop = end < 0 ? n : end + 2;
                for (int k = i; k < stop; k++) if (text.charAt(k) == '\n') line++;
                i = stop - 1;
                cur.append(' ');
                continue;
            }
            if (!Character.isWhitespace(c) && startLine < 0) startLine = line;
            if (c == '\'' || c == '"' || c == '`') {
                int j = i + 1;
                while (j < n) {
                    char d = text.charAt(j);
                    if (d == c) {
                        if (c == '\'' && j + 1 < n && text.charAt(j + 1) == '\'') { j += 2; continue; }
                        break;
                    }
                    j++;
                }
                int stop = Math.min(n, j + 1);
                for (int k = i; k < stop; k++) if (text.charAt(k) == '\n') line++;
                cur.append(text, i, stop);
                i = stop - 1;
            } else if (c == ';') {
                flush(cur, out, startLine);
                startLine = -1;
            } else if (c == '/' && lineIsOnly(text, i)) {
                flush(cur, out, startLine);
                startLine = -1;
            } else {
                if (c == '\n') line++;
                cur.append(c);
            }
        }
        flush(cur, out, startLine);
        return out;
    }

    private static boolean lineIsOnly(String text, int i) {
        int s = text.lastIndexOf('\n', i - 1) + 1;
        int e = text.indexOf('\n', i);
        if (e < 0) e = text.length();
        return text.substring(s, e).strip().equals("/");
    }

    private static void flush(StringBuilder cur, List<Chunk> out, int startLine) {
        String s = cur.toString().strip();
        if (!s.isEmpty()) out.add(new Chunk(s, Math.max(startLine, 1)));
        cur.setLength(0);
    }
}
