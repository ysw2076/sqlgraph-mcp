package io.sqlgraph.core;

import java.util.Locale;

/** 식별자 정규화 유틸. 따옴표/백틱/대괄호를 벗기고 대문자로 맞춘다. */
public final class Names {
    private Names() {}

    public static String unquote(String id) {
        if (id == null) return null;
        String s = id.strip();
        if (s.length() >= 2) {
            char a = s.charAt(0), b = s.charAt(s.length() - 1);
            if ((a == '"' && b == '"') || (a == '`' && b == '`') || (a == '[' && b == ']')) {
                s = s.substring(1, s.length() - 1);
            }
        }
        return s;
    }

    /** 비교용 키 (따옴표 제거 + 대문자) */
    public static String key(String id) {
        String s = unquote(id);
        return s == null ? null : s.toUpperCase(Locale.ROOT);
    }

    /** "SCHEMA.TABLE" → TABLE 부분만 */
    public static String lastPart(String qualified) {
        if (qualified == null) return null;
        int i = qualified.lastIndexOf('.');
        return i < 0 ? qualified : qualified.substring(i + 1);
    }
}
