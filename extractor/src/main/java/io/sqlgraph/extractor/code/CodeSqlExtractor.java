package io.sqlgraph.extractor.code;

import io.sqlgraph.core.SqlComments;
import io.sqlgraph.core.model.ExtractedSql;
import io.sqlgraph.core.model.SourceLocation;
import io.sqlgraph.core.model.SqlKind;
import io.sqlgraph.core.spi.SqlExtractor;
import io.sqlgraph.extractor.code.CodeLexer.Lang;
import io.sqlgraph.extractor.code.CodeLexer.T;
import io.sqlgraph.extractor.code.CodeLexer.Tok;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 프로그램 소스 안의 SQL 을 추출한다.
 *
 * <ul>
 *   <li>문자열 리터럴 SQL: Java·Kotlin·Scala·Groovy·C#·JS/TS·Go·PHP·Python·Ruby.
 *       {@code "SELECT .. " + "FROM .."}, StringBuilder.append 체인, {@code sql += ".."} 누적을 한 문장으로 합친다.
 *       문자열 사이에 끼운 변수·문자열 보간은 동적 요소({@code ${..}})로 표시한다</li>
 *   <li>JPA: {@code @Query}, {@code createQuery}, {@code @NamedQuery} 의 JPQL 은 엔티티 매핑으로
 *       실제 테이블·컬럼 SQL 로 바꿔서 분석한다. {@code nativeQuery = true}, {@code createNativeQuery} 는 그대로</li>
 *   <li>MyBatis 어노테이션 매퍼({@code @Select}, {@code @Insert} …): id 는 {@code 패키지.인터페이스.메서드}</li>
 * </ul>
 *
 * <p>테스트 코드(src/test, *Test.java, test_*.py, *.spec.ts …)는 건너뛴다.
 */
public final class CodeSqlExtractor implements SqlExtractor {

    private static final Pattern TEST_PATH = Pattern.compile(
            "(?i)(^|/)(src/test|src/androidTest|test|tests|__tests__|spec|testdata|fixtures?|migrations?)/"
                    + "|(Test|Tests|IT|Spec)\\.(java|kt|scala|groovy|cs)$|_test\\.(go|py|rb)$|(^|/)test_[^/]*\\.py$"
                    + "|\\.(spec|test)\\.[jt]sx?$|\\.min\\.js$|\\.d\\.ts$");
    private static final Pattern SPRING_CONFIG = Pattern.compile("(^|/)application(-[\\w.]+)?\\.(ya?ml|properties)$");

    /** SQL 로 볼 문자열: 앞 공백·주석을 뗀 뒤 문장 시작 키워드 + 문장 구조 */
    private static final Pattern SQL_START = Pattern.compile(
            "(?is)^(?:SELECT\\s.+?\\sFROM\\s+\\S|WITH\\s+(?:RECURSIVE\\s+)?\\w+.*?\\bAS\\s*\\(.*\\bSELECT\\b|INSERT\\s+(?:IGNORE\\s+)?INTO\\s+\\S"
                    + "|UPDATE\\s+\\S+(?:\\s+\\w+)?\\s+SET\\s|DELETE\\s+(?:FROM\\s+)?\\S+|MERGE\\s+INTO\\s+\\S|REPLACE\\s+INTO\\s+\\S"
                    + "|UPSERT\\s+INTO\\s+\\S|\\{\\s*(?:\\?\\s*=\\s*)?CALL\\s+\\S|CALL\\s+[\\w.]+\\s*\\()");
    private static final Pattern JPQL_START = Pattern.compile(
            "(?is)^(?:SELECT\\s.+?\\sFROM\\s+\\S|FROM\\s+[A-Za-z_]\\w*|UPDATE\\s+\\S+.*\\sSET\\s|DELETE\\s+(?:FROM\\s+)?\\S+|INSERT\\s+INTO\\s+\\S)");
    /** 영문 문장(예: "Select a file from disk")을 SQL 로 오인하지 않도록 */
    private static final Pattern SQL_SIGNAL = Pattern.compile(
            "[*=,?()]|\\b(?i:WHERE|JOIN|GROUP\\s+BY|ORDER\\s+BY|UNION|VALUES|SET|LIMIT|DUAL)\\b|:[A-Za-z_]\\w*|\\.\\w");
    private static final Pattern LEADING_COMMENT = Pattern.compile("^\\s*(?:/\\*.*?\\*/\\s*|--[^\\n]*\\n\\s*)*", Pattern.DOTALL);

    private static final Set<String> NATIVE_CALLS = Set.of("createNativeQuery", "createSQLQuery", "createNativeMutationQuery");
    private static final Set<String> JPQL_CALLS = Set.of("createQuery", "createSelectionQuery", "createMutationQuery");
    private static final Set<String> MYBATIS_ANN = Set.of("Select", "Insert", "Update", "Delete");
    private static final Set<String> CONTROL = Set.of(
            "if", "for", "while", "switch", "catch", "synchronized", "try", "else", "do", "when", "using", "lock",
            "foreach", "fixed", "return", "new", "func", "function", "fun", "def", "elif", "unless", "until",
            "with", "match", "case", "select", "go", "defer", "throw", "assert", "await", "typeof", "sizeof");

    private final JpaModel jpa = new JpaModel();

    @Override
    public String name() {
        return "code-sql";
    }

    @Override
    public boolean supports(String relativePath) {
        if (SPRING_CONFIG.matcher(relativePath).find()) return true;   // JPA 명명 규칙 확인용 (prepare 만)
        return Lang.of(relativePath) != null && !TEST_PATH.matcher(relativePath).find();
    }

    @Override
    public boolean needsPrepare() {
        return true;
    }

    @Override
    public void prepare(String relativePath, String content) {
        if (SPRING_CONFIG.matcher(relativePath).find()) {
            if (content.contains("PhysicalNamingStrategyStandardImpl")) jpa.usePhysicalNamesAsIs();
            return;
        }
        Lang lang = Lang.of(relativePath);
        if (lang == Lang.JAVA || lang == Lang.KOTLIN) jpa.addSource(content, lang == Lang.KOTLIN);
    }

    @Override
    public List<ExtractedSql> extract(String relativePath, String content) {
        if (SPRING_CONFIG.matcher(relativePath).find()) return List.of();
        Lang lang = Lang.of(relativePath);
        if (lang == null) return List.of();
        if (!mayContainSql(content)) return List.of();
        List<Tok> toks = CodeLexer.lex(content, lang);
        String[][] own = owners(toks, content, lang, relativePath);
        String[] owners = own[0];
        String[] classOwners = own[1];
        LineIndex lines = new LineIndex(content);

        List<ExtractedSql> out = new ArrayList<>();
        boolean[] used = new boolean[toks.size()];
        Map<String, Integer> seen = new HashMap<>();
        for (int i = 0; i < toks.size(); i++) {
            if (used[i] || toks.get(i).type() != T.STR) continue;
            Group g = new Group();
            int arrayClose = arrayInitializer(toks, i);
            if (arrayClose > 0) {
                // @Update({"<script>", "UPDATE ..", "</script>"}) : 원소를 공백으로 이어 붙인다 (MyBatis 와 같음)
                for (int k = i; k < arrayClose; k++) {
                    if (toks.get(k).type() != T.STR || used[k]) continue;
                    if (g.sb.length() > 0) g.sb.append(' ');
                    k = chain(toks, k, g, lang, used);
                }
            } else {
                int end = chain(toks, i, g, lang, used);
                continuation(toks, i, end, g, lang, used);
            }

            Context ctx = context(toks, i);
            String text = g.sb.toString();
            if (text.strip().startsWith("<script>")) text = stripScript(text);
            String body = LEADING_COMMENT.matcher(text).replaceFirst("").strip();
            boolean jpql = ctx.jpql || (!ctx.nativeSql && looksLikeJpql(body, lang));
            String sql;
            if (jpql) {
                if (!JPQL_START.matcher(body).find()) continue;
                String translated = jpa.isEmpty() ? null : jpa.translate(body);
                if (translated == null) {
                    if (!ctx.jpql) continue;   // 문맥 없이 엔티티도 모르면 SQL 로 보지 않음
                    translated = body;
                }
                sql = translated + " /* JPQL: " + body.replace("*/", "* /").replaceAll("\\s+", " ") + " */";
            } else {
                if (!SQL_START.matcher(body).find() || !SQL_SIGNAL.matcher(body).find()) continue;
                if (!sameCase(body)) continue;
                sql = placeholders(body);
            }
            String owner = ctx.annotatedMethod != null ? ownerFromAnnotation(classOwners[i], ctx.annotatedMethod) : owners[i];
            int n = seen.merge(owner, 1, Integer::sum);
            if (n > 1) owner = owner + "#" + n;
            SqlKind kind = SqlKind.guess(LEADING_COMMENT.matcher(sql).replaceFirst(""));
            out.add(new ExtractedSql(SqlComments.lineToBlock(sql).replaceAll("\\s+", " ").strip(), owner, kind,
                    new SourceLocation(relativePath, lines.line(toks.get(i).start())), g.dynamic));
        }
        return out;
    }

    // ============================================================ 문자열 합치기

    private static final class Group {
        final StringBuilder sb = new StringBuilder();
        boolean dynamic;
        String var;

        void str(Tok t) {
            sb.append(t.text());
            dynamic |= t.dynamic();
        }

        void expr(String e) {
            sb.append(CodeLexer.dyn(e));
            dynamic = true;
        }
    }

    /** i 가 어노테이션 인자 배열 {@code ({"a", "b"})} / {@code = {"a", "b"}} 의 첫 원소면 닫는 } 위치, 아니면 -1 */
    private static int arrayInitializer(List<Tok> toks, int i) {
        Tok open = at(toks, i - 1);
        Tok before = at(toks, i - 2);
        if (open == null || !open.is(T.OPEN, "{") || before == null) return -1;
        if (!(before.is(T.OPEN, "(") || before.op("="))) return -1;
        int close = matching(toks, i - 1);
        for (int k = i; k < close; k++) {
            Tok t = toks.get(k);
            if (t.type() == T.OPEN || t.type() == T.SEMI) return -1;   // 단순 문자열 배열만
        }
        return close;
    }

    private static boolean concatOp(Tok t, Lang lang) {
        if (t.type() != T.OP) return false;
        if (lang == Lang.PHP) return t.text().equals(".");
        return t.text().equals("+");
    }

    /**
     * i 의 문자열부터 연결된 조각을 g 에 모으고, 마지막으로 소비한 토큰 위치를 돌려준다.
     */
    private static int chain(List<Tok> toks, int i, Group g, Lang lang, boolean[] used) {
        int j = i;
        g.str(toks.get(i));
        used[i] = true;
        while (true) {
            Tok next = at(toks, j + 1);
            if (next == null) break;
            if (concatOp(next, lang)) {
                int k = j + 2;
                Tok operand = at(toks, k);
                if (operand == null) break;
                if (operand.type() == T.STR) {
                    g.str(operand);
                    used[k] = true;
                    j = k;
                } else {
                    int e = expression(toks, k, lang);
                    if (e < k) break;
                    g.expr(text(toks, k, e));
                    j = e;
                }
                continue;
            }
            if (lang == Lang.PYTHON && next.type() == T.STR) {
                g.str(next);   // 인접 문자열 자동 연결
                used[j + 1] = true;
                j++;
                continue;
            }
            // StringBuilder: .append("..").append(x)
            if (next.is(T.CLOSE, ")") && isAppend(toks, j + 2)) {
                int k = j + 5;
                Tok operand = at(toks, k);
                if (operand == null) break;
                if (operand.type() == T.STR && at(toks, k + 1) != null && at(toks, k + 1).is(T.CLOSE, ")")) {
                    g.str(operand);
                    used[k] = true;
                    j = k;
                } else {
                    int e = expression(toks, k, lang);
                    if (e < k) break;
                    // append 인자 안의 연결 ("A" + x) 처리
                    j = appendExpr(toks, k, e, g, lang, used);
                }
                continue;
            }
            break;
        }
        return j;
    }

    /** append 인자를 붙이고 마지막으로 소비한 토큰 위치를 돌려준다 (안쪽 chain 이 뒤의 .append 까지 읽었을 수 있음) */
    private static int appendExpr(List<Tok> toks, int k, int e, Group g, Lang lang, boolean[] used) {
        if (toks.get(k).type() == T.STR) {
            Group sub = new Group();
            int end = chain(toks, k, sub, lang, used);
            g.sb.append(sub.sb);
            g.dynamic |= sub.dynamic;
            if (end < e) g.expr(text(toks, end + 1, e));
            return Math.max(end, e);
        }
        g.expr(text(toks, k, e));
        return e;
    }

    /** toks[j] 부터 ". append (" 인지 */
    private static boolean isAppend(List<Tok> toks, int j) {
        Tok dot = at(toks, j);
        Tok name = at(toks, j + 1);
        Tok open = at(toks, j + 2);
        return dot != null && dot.op(".") && name != null && name.type() == T.IDENT
                && (name.text().equals("append") || name.text().equals("Append") || name.text().equals("AppendLine"))
                && open != null && open.is(T.OPEN, "(");
    }

    /** k 에서 시작하는 피연산자 식의 마지막 토큰 위치 (깊이 0 의 연결 연산자/구분자 직전까지) */
    private static int expression(List<Tok> toks, int k, Lang lang) {
        int depth = 0;
        int j = k;
        int last = k - 1;
        for (; j < toks.size() && j < k + 60; j++) {
            Tok t = toks.get(j);
            if (t.type() == T.OPEN) depth++;
            else if (t.type() == T.CLOSE) {
                if (depth == 0) break;
                depth--;
            } else if (depth == 0) {
                if (t.type() == T.SEMI || t.type() == T.COMMA || concatOp(t, lang)) break;
                if (t.type() == T.OP && (t.text().equals("=") || t.text().equals("==") || t.text().equals("?")
                        || t.text().equals(":") || t.text().equals("+=") || t.text().equals("=>"))) break;
                if (t.type() == T.STR && j > k) break;
            }
            last = j;
        }
        return last;
    }

    /**
     * {@code sql = "..."} 다음 문장들의 {@code sql += ".."}, {@code sql .= ..}, {@code sql = sql + ..},
     * {@code sb.append(..)} 를 같은 SQL 로 이어 붙인다.
     */
    private static void continuation(List<Tok> toks, int start, int end, Group g, Lang lang, boolean[] used) {
        String var = assignedVar(toks, start);
        if (var == null) return;
        // sql = ("..." "...") / sb.append("...") 처럼 여는 괄호 안에서 시작했으면 그 괄호가 닫히는 것은 정상
        Tok opener = at(toks, start - 1);
        int depth = opener != null && opener.is(T.OPEN, "(") ? 1 : 0;
        int j = end + 1;
        int guard = 0;
        while (j < toks.size() && guard++ < 4000) {
            Tok t = toks.get(j);
            if (t.type() == T.OPEN) depth++;
            if (t.type() == T.CLOSE) {
                if (--depth < 0) return;   // 블록이 끝남
            }
            if (t.type() == T.IDENT && t.text().equals(var) && !prevIsDot(toks, j)) {
                Tok op = at(toks, j + 1);
                if (op != null && (op.op("+=") || op.op(".="))) {
                    int k = j + 2;
                    j = appendOperand(toks, k, g, lang, used);
                    continue;
                }
                if (op != null && op.op("=")) {
                    Tok v2 = at(toks, j + 2);
                    Tok plus = at(toks, j + 3);
                    if (v2 != null && v2.type() == T.IDENT && v2.text().equals(var) && plus != null && concatOp(plus, lang)) {
                        j = appendOperand(toks, j + 4, g, lang, used);
                        continue;
                    }
                    return;   // 다른 값으로 다시 할당
                }
                if (op != null && op.op(".") && isAppend(toks, j + 1)) {
                    int k = j + 4;
                    Tok operand = at(toks, k);
                    if (operand == null) return;
                    int e = expression(toks, k, lang);
                    if (e < k) return;
                    j = appendExpr(toks, k, e, g, lang, used) + 1;
                    // .append(..).append(..) 체인
                    while (at(toks, j) != null && at(toks, j).is(T.CLOSE, ")") && isAppend(toks, j + 1)) {
                        k = j + 4;
                        e = expression(toks, k, lang);
                        if (e < k) break;
                        j = appendExpr(toks, k, e, g, lang, used) + 1;
                    }
                    if (at(toks, j) != null && at(toks, j).is(T.CLOSE, ")")) j++;   // 마지막 append( 의 닫는 괄호
                    continue;
                }
            }
            j++;
        }
    }

    private static int appendOperand(List<Tok> toks, int k, Group g, Lang lang, boolean[] used) {
        Tok operand = at(toks, k);
        if (operand == null) return k;
        if (operand.type() == T.STR) return chain(toks, k, g, lang, used) + 1;
        int e = expression(toks, k, lang);
        if (e < k) return k + 1;
        g.expr(text(toks, k, e));
        // x + "..." 형태
        Tok after = at(toks, e + 1);
        if (after != null && concatOp(after, lang) && at(toks, e + 2) != null && at(toks, e + 2).type() == T.STR) {
            return chain(toks, e + 2, g, lang, used) + 1;
        }
        return e + 1;
    }

    /** 문자열 그룹 시작이 {@code 변수 =} / {@code new StringBuilder(} / {@code 변수.append(} 의 대상이면 변수명 */
    private static String assignedVar(List<Tok> toks, int start) {
        Tok p1 = at(toks, start - 1);
        Tok p2 = at(toks, start - 2);
        if (p1 != null && (p1.op("=") || p1.op(":=") || p1.op("+=") || p1.op(".=")) && p2 != null && p2.type() == T.IDENT) {
            return p2.text();
        }
        // Python: sql = ("..." "...")
        Tok p3 = at(toks, start - 3);
        if (p1 != null && p1.is(T.OPEN, "(") && p2 != null && p2.op("=") && p3 != null && p3.type() == T.IDENT) {
            return p3.text();
        }
        // sb.append("...") 로 시작
        if (p1 != null && p1.is(T.OPEN, "(") && p2 != null && p2.type() == T.IDENT
                && (p2.text().equals("append") || p2.text().equals("Append") || p2.text().equals("AppendLine"))) {
            Tok dot = at(toks, start - 3);
            Tok v = at(toks, start - 4);
            if (dot != null && dot.op(".") && v != null && v.type() == T.IDENT) return v.text();
        }
        // StringBuilder sb = new StringBuilder("...")
        if (p1 != null && p1.is(T.OPEN, "(") && p2 != null && p2.type() == T.IDENT && p2.text().endsWith("StringBuilder")) {
            Tok nw = at(toks, start - 3);
            Tok eq = at(toks, start - 4);
            Tok v = at(toks, start - 5);
            if (nw != null && nw.text().equals("new") && eq != null && eq.op("=") && v != null && v.type() == T.IDENT) {
                return v.text();
            }
        }
        return null;
    }

    private static boolean prevIsDot(List<Tok> toks, int j) {
        Tok p = at(toks, j - 1);
        return p != null && (p.op(".") || p.op("->") || p.op("?."));
    }

    // =============================================================== 문맥

    /**
     * @param jpql            JPQL 로 확실한 자리 (@Query, createQuery, @NamedQuery)
     * @param nativeSql       네이티브 SQL 로 확실한 자리
     * @param annotatedMethod 어노테이션 인자면 그 어노테이션이 붙은 메서드명
     */
    private record Context(boolean jpql, boolean nativeSql, String annotatedMethod) {}

    private static Context context(List<Tok> toks, int i) {
        // 감싸는 여는 괄호 찾기
        int depth = 0;
        for (int k = i - 1; k >= 0 && k > i - 400; k--) {
            Tok t = toks.get(k);
            if (t.type() == T.CLOSE) depth++;
            else if (t.type() == T.OPEN) {
                if (depth == 0) {
                    // 어노테이션 인자 배열 ({"..", ".."}) 은 건너뛰고 바깥 괄호를 본다
                    Tok before = at(toks, k - 1);
                    if (t.text().equals("{") && before != null && (before.is(T.OPEN, "(") || before.op("="))) continue;
                    if (!t.text().equals("(")) return new Context(false, false, null);
                    Tok name = at(toks, k - 1);
                    if (name == null || name.type() != T.IDENT) return new Context(false, false, null);
                    Tok at = at(toks, k - 2);
                    boolean annotation = at != null && at.type() == T.AT;
                    // @jakarta.persistence.Query 같은 전체 이름
                    if (!annotation && at != null && at.op(".")) {
                        int q = k - 2;
                        while (q >= 1 && at(toks, q).op(".") && at(toks, q - 1).type() == T.IDENT) q -= 2;
                        annotation = at(toks, q) != null && at(toks, q).type() == T.AT;
                    }
                    String n = name.text();
                    if (annotation) {
                        int close = matching(toks, k);
                        String method = methodAfter(toks, close);
                        if (n.equals("Query")) {
                            boolean nat = hasArgTrue(toks, k, close, "nativeQuery");
                            return new Context(!nat, nat, method);
                        }
                        if (n.equals("NamedQuery")) return new Context(true, false, null);
                        if (n.equals("NamedNativeQuery")) return new Context(false, true, null);
                        if (MYBATIS_ANN.contains(n)) return new Context(false, true, method);
                        return new Context(false, false, null);
                    }
                    if (JPQL_CALLS.contains(n)) return new Context(true, false, null);
                    if (NATIVE_CALLS.contains(n)) return new Context(false, true, null);
                    return new Context(false, false, null);
                }
                depth--;
            } else if (depth == 0 && (t.type() == T.SEMI)) {
                break;
            }
        }
        return new Context(false, false, null);
    }

    private static int matching(List<Tok> toks, int open) {
        int depth = 0;
        for (int k = open; k < toks.size(); k++) {
            Tok t = toks.get(k);
            if (t.type() == T.OPEN) depth++;
            else if (t.type() == T.CLOSE && --depth == 0) return k;
        }
        return toks.size() - 1;
    }

    private static boolean hasArgTrue(List<Tok> toks, int open, int close, String arg) {
        for (int k = open; k < close; k++) {
            if (toks.get(k).type() == T.IDENT && toks.get(k).text().equals(arg)
                    && at(toks, k + 1) != null && at(toks, k + 1).op("=")
                    && at(toks, k + 2) != null && at(toks, k + 2).text().equals("true")) return true;
        }
        return false;
    }

    /** 어노테이션 뒤에 선언된 메서드 이름 (다른 어노테이션은 건너뜀) */
    private static String methodAfter(List<Tok> toks, int close) {
        int k = close + 1;
        while (k < toks.size() && k < close + 80) {
            Tok t = toks.get(k);
            if (t.type() == T.AT) {
                k++;
                while (at(toks, k) != null && (at(toks, k).type() == T.IDENT || at(toks, k).op("."))) k++;
                if (at(toks, k) != null && at(toks, k).is(T.OPEN, "(")) k = matching(toks, k) + 1;
                continue;
            }
            if (t.type() == T.IDENT && at(toks, k + 1) != null && at(toks, k + 1).is(T.OPEN, "(")
                    && !CONTROL.contains(t.text())) {
                return t.text();
            }
            if (t.type() == T.SEMI || t.is(T.OPEN, "{")) break;
            k++;
        }
        return null;
    }

    private boolean looksLikeJpql(String body, Lang lang) {
        if ((lang != Lang.JAVA && lang != Lang.KOTLIN) || jpa.isEmpty()) return false;
        Matcher m = Pattern.compile("(?i)\\b(?:FROM|UPDATE|JOIN)\\s+([A-Za-z_]\\w*)").matcher(body);
        while (m.find()) {
            String n = m.group(1);
            if (jpa.isEntityName(n)) {
                JpaModel.Entity e = jpa.entity(n);
                if (e != null && !jpa.table(e).equals(n)) return true;
            }
        }
        return false;
    }

    // =============================================================== 소유자

    /** 토큰마다 "패키지.클래스.메서드" (언어별 근사) */
    private static String[][] owners(List<Tok> toks, String content, Lang lang, String path) {
        String[] r = new String[toks.size()];
        String[] cls = new String[toks.size()];
        String stem = path.substring(path.lastIndexOf('/') + 1);
        if (stem.contains(".")) stem = stem.substring(0, stem.lastIndexOf('.'));
        String pkg = packageName(content, lang);

        if (lang == Lang.PYTHON || lang == Lang.RUBY) {
            LineIndex li = new LineIndex(content);
            List<int[]> defs = new ArrayList<>();      // {offset, indent}
            List<String> names = new ArrayList<>();
            Matcher m = Pattern.compile("(?m)^([ \\t]*)(def|class|module)\\s+(?:self\\.)?([A-Za-z_]\\w*[?!]?)").matcher(content);
            while (m.find()) {
                defs.add(new int[]{m.start(), m.group(1).length(), m.group(2).equals("def") ? 1 : 0});
                names.add(m.group(3));
            }
            for (int k = 0; k < toks.size(); k++) {
                int off = toks.get(k).start();
                Deque<String> path2 = new ArrayDeque<>();
                int curIndent = Integer.MAX_VALUE;
                int col = li.column(off);
                for (int d = defs.size() - 1; d >= 0; d--) {
                    int[] df = defs.get(d);
                    if (df[0] > off) continue;
                    if (df[1] < curIndent && df[1] < col) {
                        path2.addFirst(names.get(d));
                        curIndent = df[1];
                    }
                    if (curIndent == 0) break;
                }
                r[k] = join(pkg != null ? pkg : stem, path2);
            }
            return new String[][]{r, r};
        }

        Deque<String> stack = new ArrayDeque<>();   // 블록마다 라벨 (없으면 "")
        Map<Integer, String> declaredFunc = new HashMap<>();   // 블록 깊이 → 마지막으로 선언된 fun/func/def 이름
        String pendingClass = null;
        String candidate = null;
        int parenDepth = 0;
        for (int k = 0; k < toks.size(); k++) {
            Tok t = toks.get(k);
            List<String> labels = new ArrayList<>();
            for (var it = stack.descendingIterator(); it.hasNext(); ) {
                String l = it.next();
                if (!l.isEmpty()) labels.add(l);
            }
            // Kotlin/Scala 식 본문 함수 (fun x() = ...): 블록이 없으므로 같은 깊이의 마지막 선언을 쓴다
            cls[k] = join(pkg != null ? pkg : stem, labels);
            String fn = declaredFunc.get(stack.size());
            if (fn != null && (stack.isEmpty() || !stack.peek().equals(fn))) labels.add(fn);
            r[k] = join(pkg != null ? pkg : stem, labels);
            if (t.type() == T.IDENT && Set.of("fun", "def", "func", "function").contains(t.text())) {
                Tok n = at(toks, k + 1);
                if (n != null && n.is(T.OPEN, "(")) {   // Go 메서드: func (r *T) Name(
                    int close = matching(toks, k + 1);
                    n = at(toks, close + 1);
                }
                if (n != null && n.type() == T.IDENT) declaredFunc.put(stack.size(), n.text());
            }
            if (t.type() == T.IDENT && Set.of("class", "interface", "enum", "record", "object", "struct", "trait", "impl")
                    .contains(t.text())) {
                Tok n = at(toks, k + 1);
                if (n != null && n.type() == T.IDENT) pendingClass = n.text();
                continue;
            }
            if (t.is(T.OPEN, "(")) {
                Tok prev = at(toks, k - 1);
                if (parenDepth == 0 && prev != null && prev.type() == T.IDENT && !CONTROL.contains(prev.text())) {
                    candidate = prev.text();
                }
                parenDepth++;
            } else if (t.is(T.CLOSE, ")")) {
                parenDepth = Math.max(0, parenDepth - 1);
            } else if (t.is(T.OPEN, "{")) {
                String label = "";
                if (pendingClass != null) {
                    label = pendingClass;
                    pendingClass = null;
                } else if (candidate != null && parenDepth == 0) {
                    label = candidate;
                }
                candidate = null;
                stack.push(label);
            } else if (t.is(T.CLOSE, "}")) {
                declaredFunc.remove(stack.size());
                if (!stack.isEmpty()) stack.pop();
                candidate = null;
            } else if (t.type() == T.SEMI) {
                candidate = null;
                if (parenDepth == 0) pendingClass = null;
            }
        }
        return new String[][]{r, cls};
    }

    private static String packageName(String content, Lang lang) {
        Pattern p = switch (lang) {
            case JAVA, KOTLIN, SCALA, GROOVY -> Pattern.compile("(?m)^\\s*package\\s+([\\w.]+)");
            case CSHARP -> Pattern.compile("(?m)^\\s*namespace\\s+([\\w.]+)");
            case PHP -> Pattern.compile("(?m)^\\s*namespace\\s+([\\w\\\\]+)\\s*;");
            case GO -> Pattern.compile("(?m)^\\s*package\\s+(\\w+)");
            default -> null;
        };
        if (p == null) return null;
        Matcher m = p.matcher(content);
        return m.find() ? m.group(1).replace('\\', '.') : null;
    }

    private static String join(String base, Iterable<String> parts) {
        StringBuilder sb = new StringBuilder(base);
        for (String p : parts) sb.append('.').append(p);
        return sb.toString();
    }

    /** 어노테이션이 붙은 메서드: 현재 위치(클래스 본문)의 소유자 + 메서드명 */
    private static String ownerFromAnnotation(String owner, String method) {
        return owner + "." + method;
    }

    // =============================================================== 정리

    /** MyBatis 어노테이션의 &lt;script&gt; 동적 SQL: 태그를 지우고 내용만 */
    private static String stripScript(String s) {
        return s.replaceAll("(?s)<!\\[CDATA\\[|]]>", " ")
                .replaceAll("(?is)<where\\s*>", " WHERE 1=1 AND ")
                .replaceAll("(?is)<set\\s*>", " SET ")
                .replaceAll("(?s)<[^>]+>", " ")
                .replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&")
                .replaceAll("(?i)WHERE 1=1 AND\\s+(AND|OR)\\b", "WHERE 1=1 $1")
                .replaceAll(",\\s*(WHERE)\\b", " $1");
    }

    /** 언어별 바인드 표기를 ? 로 (%s, %(name)s, $1, {0}) */
    private static String placeholders(String s) {
        return s.replaceAll("%\\(\\w+\\)[sd]", "?")
                .replaceAll("(?<![%\\w])%[sd]\\b", "?")
                .replaceAll("(?<![\\w$])\\$\\d+\\b", "?")
                .replaceAll("(?<![$\\w])\\{\\d+}", "?");
    }

    /** "Select ... From" 처럼 첫 글자만 대문자인 키워드는 영문 문장일 가능성이 높다 */
    private static boolean sameCase(String body) {
        Matcher m = Pattern.compile("^\\s*(\\{\\s*)?([A-Za-z]+)").matcher(body);
        if (!m.find()) return false;
        String kw = m.group(2);
        return kw.equals(kw.toUpperCase(Locale.ROOT)) || kw.equals(kw.toLowerCase(Locale.ROOT));
    }

    /** 소스에 SQL 키워드가 하나도 없으면 토큰화하지 않는다 (속도) */
    private static boolean mayContainSql(String content) {
        String up = content.length() > 2_000_000 ? content.substring(0, 2_000_000) : content;
        return Pattern.compile("(?i)\\b(SELECT|INSERT|UPDATE|DELETE|MERGE|CALL|FROM)\\b").matcher(up).find();
    }

    private static Tok at(List<Tok> toks, int k) {
        return k >= 0 && k < toks.size() ? toks.get(k) : null;
    }

    private static String text(List<Tok> toks, int from, int to) {
        StringBuilder sb = new StringBuilder();
        for (int k = from; k <= to && k < toks.size(); k++) {
            if (sb.length() > 0) sb.append(' ');
            Tok t = toks.get(k);
            sb.append(t.type() == T.STR ? "'" + t.text() + "'" : t.text());
        }
        return sb.toString().replace(" . ", ".").replace(" ( ", "(").replace(" )", ")");
    }

    /** 오프셋 → 줄/열 */
    private static final class LineIndex {
        private final int[] starts;

        LineIndex(String s) {
            List<Integer> l = new ArrayList<>();
            l.add(0);
            for (int k = 0; k < s.length(); k++) if (s.charAt(k) == '\n') l.add(k + 1);
            starts = l.stream().mapToInt(Integer::intValue).toArray();
        }

        int line(int off) {
            int lo = 0;
            int hi = starts.length - 1;
            while (lo < hi) {
                int mid = (lo + hi + 1) >>> 1;
                if (starts[mid] <= off) lo = mid;
                else hi = mid - 1;
            }
            return lo + 1;
        }

        int column(int off) {
            return off - starts[line(off) - 1];
        }
    }
}
