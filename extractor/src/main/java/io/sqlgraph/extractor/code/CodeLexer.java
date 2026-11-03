package io.sqlgraph.extractor.code;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 여러 언어 소스를 SQL 추출에 필요한 만큼만 토큰으로 나눈다.
 * 주석은 버리고, 문자열 리터럴은 값(문자열 안 보간은 {@code ${식}} 표시로 바꿈)으로 돌려준다.
 * 정확한 구문 분석이 아니라 "문자열 + 연결 연산자 + 괄호 구조"를 놓치지 않는 것이 목표다.
 */
final class CodeLexer {

    enum Lang {
        JAVA, KOTLIN, SCALA, GROOVY, CSHARP, JS, GO, PHP, PYTHON, RUBY;

        static Lang of(String path) {
            String p = path.toLowerCase(Locale.ROOT);
            int dot = p.lastIndexOf('.');
            String ext = dot < 0 ? "" : p.substring(dot + 1);
            return switch (ext) {
                case "java" -> JAVA;
                case "kt", "kts" -> KOTLIN;
                case "scala" -> SCALA;
                case "groovy" -> GROOVY;
                case "cs" -> CSHARP;
                case "js", "jsx", "ts", "tsx", "mjs", "cjs" -> JS;
                case "go" -> GO;
                case "php" -> PHP;
                case "py" -> PYTHON;
                case "rb" -> RUBY;
                default -> null;
            };
        }

        boolean slashComments() {
            return this != PYTHON && this != RUBY;
        }

        boolean hashComments() {
            return this == PYTHON || this == RUBY || this == PHP;
        }
    }

    enum T { STR, IDENT, NUM, OP, OPEN, CLOSE, SEMI, COMMA, AT, OTHER }

    /**
     * @param text    STR 이면 문자열 값(보간은 ${..}), 그 외는 원문
     * @param dynamic STR 안에 런타임 보간이 있었는지
     */
    record Tok(T type, String text, int start, int end, boolean dynamic) {
        boolean is(T t, String s) {
            return type == t && text.equals(s);
        }

        boolean op(String s) {
            return type == T.OP && text.equals(s);
        }
    }

    /** 보간 표시. SqlAnalyzer 가 ${..} 를 동적 요소로 처리한다 */
    static String dyn(String expr) {
        String e = expr == null ? "" : expr.replaceAll("[\\s{}]+", " ").strip();
        if (e.length() > 40) e = e.substring(0, 40);
        return "${" + (e.isEmpty() ? "expr" : e) + "}";
    }

    private static final Set<String> SQL_TAGS = Set.of("sql", "SQL", "Sql", "sqlFragment", "unsafe");

    private final String src;
    private final Lang lang;
    private final List<Tok> out = new ArrayList<>();
    private int i;

    private CodeLexer(String src, Lang lang) {
        this.src = src;
        this.lang = lang;
    }

    static List<Tok> lex(String src, Lang lang) {
        CodeLexer l = new CodeLexer(src, lang);
        l.run();
        if (l.pendingSkips.isEmpty()) return l.out;
        // Ruby 히어독: 본문은 이미 STR 토큰으로 넣었으므로 본문 범위의 토큰은 버린다
        List<Tok> r = new ArrayList<>();
        outer:
        for (Tok t : l.out) {
            if (t.type() != T.STR) {
                for (int[] s : l.pendingSkips) {
                    if (t.start() >= s[0] && t.start() < s[1]) continue outer;
                }
            }
            r.add(t);
        }
        return r;
    }

    private char at(int k) {
        return k < src.length() ? src.charAt(k) : '\0';
    }

    private boolean starts(String s) {
        return src.startsWith(s, i);
    }

    private void run() {
        int n = src.length();
        while (i < n) {
            char c = src.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
                continue;
            }
            // 주석
            if (lang.slashComments() && c == '/' && at(i + 1) == '/') {
                skipLine();
                continue;
            }
            if (lang.slashComments() && c == '/' && at(i + 1) == '*') {
                int e = src.indexOf("*/", i + 2);
                i = e < 0 ? n : e + 2;
                continue;
            }
            if (lang.hashComments() && c == '#' && !(lang == Lang.PHP && at(i + 1) == '[')) {
                skipLine();
                continue;
            }
            if (lang == Lang.RUBY && c == '=' && starts("=begin") && (i == 0 || src.charAt(i - 1) == '\n')) {
                int e = src.indexOf("\n=end", i);
                i = e < 0 ? n : e + 5;
                continue;
            }
            // 문자열
            if (tryString()) continue;
            // 식별자
            if (Character.isJavaIdentifierStart(c) || (c == '$' && lang == Lang.PHP)) {
                int s = i++;
                while (i < n && (Character.isJavaIdentifierPart(src.charAt(i)))) i++;
                out.add(new Tok(T.IDENT, src.substring(s, i), s, i, false));
                continue;
            }
            if (Character.isDigit(c)) {
                int s = i++;
                while (i < n && (Character.isLetterOrDigit(src.charAt(i)) || src.charAt(i) == '.' || src.charAt(i) == '_')) i++;
                out.add(new Tok(T.NUM, src.substring(s, i), s, i, false));
                continue;
            }
            switch (c) {
                case '(', '[', '{' -> add(T.OPEN, 1);
                case ')', ']', '}' -> add(T.CLOSE, 1);
                case ';' -> add(T.SEMI, 1);
                case ',' -> add(T.COMMA, 1);
                case '@' -> add(T.AT, 1);
                default -> op();
            }
        }
    }

    private void add(T t, int len) {
        out.add(new Tok(t, src.substring(i, i + len), i, i + len, false));
        i += len;
    }

    private static final String[] OPS = {"+=", ".=", "==", "=>", "->", "::", ":=", "?.", "!=", "<=", ">=", "&&", "||"};

    private void op() {
        for (String o : OPS) {
            if (starts(o)) {
                add(T.OP, o.length());
                return;
            }
        }
        add(T.OP, 1);
    }

    private void skipLine() {
        int e = src.indexOf('\n', i);
        i = e < 0 ? src.length() : e + 1;
    }

    // ================================================================ strings

    private boolean tryString() {
        char c = src.charAt(i);
        switch (lang) {
            case PYTHON -> {
                // 접두어 r, b, u, f, rb, fr ...
                int k = i;
                while (k < i + 2 && "rRbBuUfF".indexOf(at(k)) >= 0) k++;
                char q = at(k);
                if ((q == '\'' || q == '"') && (k == i || isPrefixStart())) {
                    String prefix = src.substring(i, k).toLowerCase(Locale.ROOT);
                    boolean raw = prefix.contains("r");
                    boolean interp = prefix.contains("f");
                    int s = i;
                    i = k;
                    boolean triple = at(i + 1) == q && at(i + 2) == q;
                    readQuoted(s, triple ? String.valueOf(q).repeat(3) : String.valueOf(q), !raw, interp ? Interp.PY_FSTRING : Interp.NONE);
                    return true;
                }
                return false;
            }
            case JAVA -> {
                if (starts("\"\"\"")) return quoted("\"\"\"", true, Interp.NONE);
                if (c == '"') return quoted("\"", true, Interp.NONE);
                if (c == '\'') return charLiteral();
                return false;
            }
            case KOTLIN -> {
                if (starts("\"\"\"")) return quoted("\"\"\"", false, Interp.DOLLAR);
                if (c == '"') return quoted("\"", true, Interp.DOLLAR);
                if (c == '\'') return charLiteral();
                return false;
            }
            case SCALA -> {
                if (Character.isJavaIdentifierStart(c)) {
                    int k = i;
                    while (Character.isJavaIdentifierPart(at(k))) k++;
                    if (at(k) == '"' && isPrefixStart()) {
                        String tag = src.substring(i, k);
                        int s = i;
                        i = k;
                        Interp in = SQL_TAGS.contains(tag) ? Interp.DOLLAR_BIND : Interp.DOLLAR;
                        if (starts("\"\"\"")) readQuoted(s, "\"\"\"", false, in);
                        else readQuoted(s, "\"", true, in);
                        return true;
                    }
                    return false;
                }
                if (starts("\"\"\"")) return quoted("\"\"\"", false, Interp.NONE);
                if (c == '"') return quoted("\"", true, Interp.NONE);
                if (c == '\'') return charLiteral();
                return false;
            }
            case GROOVY -> {
                if (starts("'''")) return quoted("'''", true, Interp.NONE);
                if (starts("\"\"\"")) return quoted("\"\"\"", true, Interp.DOLLAR);
                if (c == '\'') return quoted("'", true, Interp.NONE);
                if (c == '"') return quoted("\"", true, Interp.DOLLAR);
                return false;
            }
            case CSHARP -> {
                int k = i;
                boolean dollar = false;
                boolean verbatim = false;
                while (at(k) == '$' || at(k) == '@') {
                    if (at(k) == '$') dollar = true;
                    else verbatim = true;
                    k++;
                }
                if (at(k) != '"') {
                    if (c == '\'') return charLiteral();
                    return false;
                }
                int s = i;
                i = k;
                Interp in = dollar ? Interp.BRACE : Interp.NONE;
                if (starts("\"\"\"")) {
                    int qn = 0;
                    while (at(i + qn) == '"') qn++;
                    readQuoted(s, "\"".repeat(qn), false, in);
                } else if (verbatim) {
                    readQuoted(s, "\"", false, in);   // "" 이스케이프는 readQuoted 가 처리
                } else {
                    readQuoted(s, "\"", true, in);
                }
                return true;
            }
            case JS -> {
                if (c == '`') {
                    Tok prev = out.isEmpty() ? null : out.get(out.size() - 1);
                    boolean tagged = prev != null && prev.type() == T.IDENT && prev.end() == i
                            && SQL_TAGS.contains(prev.text());
                    return quoted("`", true, tagged ? Interp.DOLLAR_BIND : Interp.JS_TEMPLATE);
                }
                if (c == '"' || c == '\'') return quoted(String.valueOf(c), true, Interp.NONE);
                return false;
            }
            case GO -> {
                if (c == '`') return quoted("`", false, Interp.NONE);
                if (c == '"') return quoted("\"", true, Interp.NONE);
                if (c == '\'') return charLiteral();
                return false;
            }
            case PHP -> {
                if (starts("<<<")) return heredoc(3, Interp.PHP_VAR);
                if (c == '\'') return quoted("'", true, Interp.NONE);
                if (c == '"') return quoted("\"", true, Interp.PHP_VAR);
                return false;
            }
            case RUBY -> {
                if (c == '<' && at(i + 1) == '<' && (at(i + 2) == '~' || at(i + 2) == '-'
                        || Character.isUpperCase(at(i + 2)) || at(i + 2) == '\'' || at(i + 2) == '"')) {
                    return heredoc(2, Interp.RUBY_HASH);
                }
                if (c == '\'') return quoted("'", true, Interp.NONE);
                if (c == '"') return quoted("\"", true, Interp.RUBY_HASH);
                return false;
            }
            default -> {
                return false;
            }
        }
    }

    /** 문자열 접두어(식별자처럼 생김) 앞이 식별자 일부가 아닌지 */
    private boolean isPrefixStart() {
        return i == 0 || !Character.isJavaIdentifierPart(src.charAt(i - 1));
    }

    private boolean charLiteral() {
        int e = i + 1;
        while (e < src.length() && e < i + 12 && src.charAt(e) != '\'') {
            if (src.charAt(e) == '\\') e++;
            e++;
        }
        int end = Math.min(src.length(), e + 1);
        out.add(new Tok(T.OTHER, src.substring(i, end), i, end, false));
        i = end;
        return true;
    }

    private boolean quoted(String q, boolean escapes, Interp interp) {
        readQuoted(i, q, escapes, interp);
        return true;
    }

    private enum Interp { NONE, DOLLAR, DOLLAR_BIND, BRACE, JS_TEMPLATE, PY_FSTRING, PHP_VAR, RUBY_HASH }

    /**
     * i 는 여는 따옴표 위치. tokenStart 는 접두어 포함 시작 위치.
     */
    private void readQuoted(int tokenStart, String q, boolean escapes, Interp interp) {
        int n = src.length();
        i += q.length();
        StringBuilder sb = new StringBuilder();
        boolean dynamic = false;
        while (i < n) {
            if (starts(q)) {
                // C# verbatim/raw, Go 등에서 "" 는 따옴표 하나
                if (!escapes && q.equals("\"") && at(i + 1) == '"' && lang == Lang.CSHARP) {
                    sb.append('"');
                    i += 2;
                    continue;
                }
                i += q.length();
                break;
            }
            char c = src.charAt(i);
            if (escapes && c == '\\' && i + 1 < n) {
                char e = src.charAt(i + 1);
                sb.append(switch (e) {
                    case 'n' -> '\n';
                    case 't' -> '\t';
                    case 'r' -> '\r';
                    default -> e;
                });
                i += 2;
                continue;
            }
            if (q.length() == 1 && c == '\n' && lang != Lang.JS && lang != Lang.GO && lang != Lang.PHP
                    && lang != Lang.RUBY && lang != Lang.GROOVY && !(lang == Lang.CSHARP && !escapes)) {
                break;   // 닫히지 않은 한 줄 문자열 — 복구
            }
            int consumed = interpolation(interp, sb);
            if (consumed > 0) {
                dynamic |= interp != Interp.DOLLAR_BIND;
                i += consumed;
                continue;
            }
            sb.append(c);
            i++;
        }
        out.add(new Tok(T.STR, sb.toString(), tokenStart, i, dynamic));
    }

    /** 보간이면 sb 에 표시를 넣고 소비한 길이를, 아니면 0 */
    private int interpolation(Interp interp, StringBuilder sb) {
        char c = src.charAt(i);
        switch (interp) {
            case DOLLAR, DOLLAR_BIND, JS_TEMPLATE -> {
                if (c == '$' && at(i + 1) == '{') {
                    int e = matchBrace(i + 1);
                    sb.append(interp == Interp.DOLLAR_BIND ? "?" : dyn(src.substring(i + 2, Math.max(i + 2, e))));
                    return e + 1 - i;
                }
                if (interp != Interp.JS_TEMPLATE && c == '$' && Character.isJavaIdentifierStart(at(i + 1))) {
                    int e = i + 1;
                    while (Character.isJavaIdentifierPart(at(e)) || (at(e) == '.' && Character.isJavaIdentifierStart(at(e + 1)))) e++;
                    sb.append(interp == Interp.DOLLAR_BIND ? "?" : dyn(src.substring(i + 1, e)));
                    return e - i;
                }
                return 0;
            }
            case BRACE, PY_FSTRING -> {
                if (c == '{' && at(i + 1) == '{') {
                    sb.append('{');
                    return 2;
                }
                if (c == '}' && at(i + 1) == '}') {
                    sb.append('}');
                    return 2;
                }
                if (c == '{') {
                    int e = matchBrace(i);
                    sb.append(dyn(src.substring(i + 1, Math.max(i + 1, e))));
                    return e + 1 - i;
                }
                return 0;
            }
            case PHP_VAR -> {
                if (c == '{' && at(i + 1) == '$') {
                    int e = matchBrace(i);
                    sb.append(dyn(src.substring(i + 1, Math.max(i + 1, e))));
                    return e + 1 - i;
                }
                if (c == '$' && Character.isJavaIdentifierStart(at(i + 1))) {
                    int e = i + 1;
                    while (Character.isJavaIdentifierPart(at(e))) e++;
                    if (at(e) == '-' && at(e + 1) == '>') {
                        e += 2;
                        while (Character.isJavaIdentifierPart(at(e))) e++;
                    }
                    sb.append(dyn(src.substring(i + 1, e)));
                    return e - i;
                }
                return 0;
            }
            case RUBY_HASH -> {
                if (c == '#' && at(i + 1) == '{') {
                    int e = matchBrace(i + 1);
                    sb.append(dyn(src.substring(i + 2, Math.max(i + 2, e))));
                    return e + 1 - i;
                }
                return 0;
            }
            default -> {
                return 0;
            }
        }
    }

    /** open 위치의 { 에 맞는 } 위치 (못 찾으면 줄 끝) */
    private int matchBrace(int open) {
        int depth = 0;
        for (int k = open; k < src.length(); k++) {
            char c = src.charAt(k);
            if (c == '{') depth++;
            else if (c == '}' && --depth == 0) return k;
            else if (c == '\n' && k - open > 200) return k;
        }
        return src.length() - 1;
    }

    /** PHP &lt;&lt;&lt;ID / Ruby &lt;&lt;~ID 히어독 */
    private boolean heredoc(int markerLen, Interp interp) {
        int s = i;
        int k = i + markerLen;
        if (at(k) == '~' || at(k) == '-') k++;
        while (at(k) == ' ') k++;
        char quote = at(k) == '\'' || at(k) == '"' ? at(k) : 0;
        if (quote != 0) k++;
        int idStart = k;
        while (Character.isJavaIdentifierPart(at(k))) k++;
        if (k == idStart) {
            add(T.OP, 1);
            return true;
        }
        String id = src.substring(idStart, k);
        if (quote != 0 && at(k) == quote) k++;
        boolean noInterp = quote == '\'';
        if (lang == Lang.RUBY) {
            // Ruby: 같은 줄의 나머지(.squish 등)는 코드로 남기고 본문은 다음 줄부터
            int lineEnd = src.indexOf('\n', k);
            if (lineEnd < 0) {
                i = k;
                return true;
            }
            int bodyStart = lineEnd + 1;
            int end = findHeredocEnd(bodyStart, id);
            String body = src.substring(bodyStart, end < 0 ? src.length() : end);
            out.add(new Tok(T.STR, interpolate(body, noInterp ? Interp.NONE : interp), s, k, !noInterp && body.contains("#{")));
            // 본문을 건너뛰기 위해 같은 줄 나머지만 계속 읽고, 본문 범위는 skip 목록으로
            pendingSkips.add(new int[]{bodyStart, end < 0 ? src.length() : skipToLineEnd(end)});
            i = k;
            return true;
        }
        int bodyStart = src.indexOf('\n', k);
        if (bodyStart < 0) {
            i = k;
            return true;
        }
        bodyStart++;
        int end = findHeredocEnd(bodyStart, id);
        int stop = end < 0 ? src.length() : end;
        String body = src.substring(bodyStart, stop);
        out.add(new Tok(T.STR, interpolate(body, noInterp ? Interp.NONE : interp), s, stop,
                !noInterp && (body.contains("$") || body.contains("{$"))));
        // 종료 식별자 다음부터 다시 토큰화 (뒤의 ; 등은 코드)
        i = Math.min(src.length(), end < 0 ? src.length() : end + leadingSpaces(end) + id.length());
        return true;
    }

    private final List<int[]> pendingSkips = new ArrayList<>();

    private int leadingSpaces(int k) {
        int c = 0;
        while (at(k + c) == ' ' || at(k + c) == '\t') c++;
        return c;
    }

    private int skipToLineEnd(int k) {
        int e = src.indexOf('\n', k);
        return e < 0 ? src.length() : e;
    }

    /** 줄 앞 공백 뒤에 id 만 있는 줄(뒤에 ; , ) . 허용)의 시작 위치 */
    private int findHeredocEnd(int from, String id) {
        int k = from;
        while (k < src.length()) {
            int le = src.indexOf('\n', k);
            String line = src.substring(k, le < 0 ? src.length() : le).strip();
            if (line.equals(id) || (line.startsWith(id) && line.length() > id.length()
                    && ";,).".indexOf(line.charAt(id.length())) >= 0)) {
                return k;
            }
            if (le < 0) break;
            k = le + 1;
        }
        return -1;
    }

    private String interpolate(String body, Interp interp) {
        if (interp == Interp.NONE) return body;
        CodeLexer sub = new CodeLexer(body, lang);
        StringBuilder sb = new StringBuilder();
        while (sub.i < body.length()) {
            int consumed = sub.interpolation(interp, sb);
            if (consumed > 0) {
                sub.i += consumed;
            } else {
                sb.append(body.charAt(sub.i));
                sub.i++;
            }
        }
        return sb.toString();
    }
}
