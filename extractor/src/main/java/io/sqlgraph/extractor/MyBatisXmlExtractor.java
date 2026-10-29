package io.sqlgraph.extractor;

import io.sqlgraph.core.model.ExtractedSql;
import io.sqlgraph.core.model.SourceLocation;
import io.sqlgraph.core.model.SqlKind;
import io.sqlgraph.core.spi.SqlExtractor;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
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
 * MyBatis 3 Mapper XML / iBatis 2 SqlMap XML 에서 쿼리를 추출한다.
 *
 * <p>동적 SQL 은 "모든 조건이 참"이라고 보고 평탄화한다.
 * <ul>
 *   <li>if / when / iBatis is* 태그: 내용 포함</li>
 *   <li>choose: 첫 번째 when 을 본 SQL 로, 나머지 when / otherwise 는 variants 로 추출
 *       (분석 결과는 합쳐서 저장되므로 어느 분기에서 쓰는 테이블·컬럼도 빠지지 않는다)</li>
 *   <li>where / set / trim: 앞뒤 AND, OR, 콤마 정리</li>
 *   <li>foreach / iterate: open + 내용 1회 + close</li>
 *   <li>include: sql 조각을 펼침. 다른 Mapper 파일의 조각도 찾는다 (prepare 단계에서 프로젝트 전체 수집).
 *       include 안의 property 값으로 조각의 ${이름} 을 치환한다. 못 찾으면 dynamic 처리</li>
 *   <li>#{..} → ?, ${..} → 동적 표시 (dynamic = true)</li>
 * </ul>
 */
public final class MyBatisXmlExtractor implements SqlExtractor {

    private static final Set<String> STATEMENT_TAGS = Set.of(
            "select", "insert", "update", "delete", "statement", "procedure");
    private static final Pattern LEADING_ANDOR = Pattern.compile("(?is)^\\s*(AND|OR)\\b");
    private static final Pattern DOLLAR = Pattern.compile("\\$\\{[^}]*}|\\$[A-Za-z_][\\w.\\[\\]]*\\$");
    private static final Pattern PROP = Pattern.compile("\\$\\{([^}]+)}");
    private static final Pattern HAS_FRAGMENT = Pattern.compile("<sql\\s[^>]*\\bid\\s*=");
    /** choose 분기를 이 수 이상 펼치지 않는다 */
    private static final int MAX_VARIANTS = 8;

    /** 조각이 정의된 namespace 와 함께 보관 (조각 안의 include 를 그 namespace 기준으로 찾기 위해) */
    private record Fragment(String namespace, Element element) {}

    /** 프로젝트 전체의 sql 조각: "namespace.id" → 조각 */
    private final Map<String, Fragment> global = new HashMap<>();
    /** namespace 없이 id 만으로 찾기: id → 조각들 (iBatis useStatementNamespaces=false 대비) */
    private final Map<String, List<Fragment>> byId = new HashMap<>();
    /** prepare 에서 파싱한 문서 재사용 */
    private final Map<String, Document> parsed = new HashMap<>();

    @Override
    public String name() {
        return "mybatis-xml";
    }

    @Override
    public boolean supports(String relativePath) {
        return relativePath.toLowerCase(Locale.ROOT).endsWith(".xml");
    }

    @Override
    public boolean needsPrepare() {
        return true;
    }

    /** 다른 파일에서 include 할 수 있도록 sql 조각을 모은다 */
    @Override
    public void prepare(String relativePath, String content) {
        if (!isMapper(content) || !HAS_FRAGMENT.matcher(content).find()) return;
        Element root = rootOf(content);
        if (root == null) return;
        parsed.put(relativePath, root.getOwnerDocument());
        String ns = root.getAttribute("namespace");
        for (Element e : children(root)) {
            if (!e.getTagName().equals("sql") || e.getAttribute("id").isEmpty()) continue;
            String id = e.getAttribute("id");
            Fragment f = new Fragment(ns, e);
            global.put(ns.isEmpty() ? id : ns + "." + id, f);
            byId.computeIfAbsent(id, k -> new ArrayList<>()).add(f);
        }
    }

    @Override
    public List<ExtractedSql> extract(String relativePath, String content) {
        // 빠른 필터: MyBatis/iBatis 파일이 아니면 DOM 파싱하지 않는다
        if (!isMapper(content)) return List.of();
        Document cached = parsed.remove(relativePath);
        Element root = cached != null ? cached.getDocumentElement() : rootOf(content);
        if (root == null) return List.of();
        String ns = root.getAttribute("namespace");

        // 이 파일의 sql 조각 (prepare 없이 단독으로 쓸 때도 같은 파일 include 는 되도록)
        Map<String, Fragment> local = new HashMap<>();
        for (Element e : children(root)) {
            if (e.getTagName().equals("sql") && !e.getAttribute("id").isEmpty()) {
                String id = e.getAttribute("id");
                Fragment f = new Fragment(ns, e);
                local.put(id, f);
                if (!ns.isEmpty()) local.put(ns + "." + id, f);
            }
        }

        List<ExtractedSql> out = new ArrayList<>();
        for (Element e : children(root)) {
            String tag = e.getTagName();
            if (!STATEMENT_TAGS.contains(tag)) continue;
            String id = e.getAttribute("id");
            if (id.isEmpty()) continue;

            Ctx ctx = new Ctx(local, ns, 0);
            String sql = clean(render(e, ctx));
            if (sql.isBlank()) continue;
            boolean dynamic = ctx.dynamic || DOLLAR.matcher(sql).find();

            // choose 의 나머지 분기: k 번째 분기를 고른 SQL 을 따로 만든다 (모든 choose 에 같은 k 적용)
            List<String> variants = new ArrayList<>();
            int branches = ctx.maxBranches;   // 안쪽 choose 는 바깥 분기를 펼쳐야 보이므로 늘어날 수 있다
            for (int k = 1; k < Math.min(branches, MAX_VARIANTS + 1); k++) {
                Ctx vc = new Ctx(local, ns, k);
                String v = clean(render(e, vc));
                if (!v.isBlank() && !v.equals(sql) && !variants.contains(v)) variants.add(v);
                dynamic |= vc.dynamic;
                branches = Math.max(branches, vc.maxBranches);
            }

            String owner = ns.isEmpty() ? id : ns + "." + id;
            int line = lineOf(content, tag, id);
            out.add(new ExtractedSql(sql, owner, kindOf(tag, e), new SourceLocation(relativePath, line),
                    dynamic, variants));
        }
        return out;
    }

    private static boolean isMapper(String content) {
        return content.contains("<mapper") || content.contains("<sqlMap");
    }

    private static Element rootOf(String content) {
        try {
            Element root = parse(content).getDocumentElement();
            String n = root.getTagName();
            return n.equals("mapper") || n.equals("sqlMap") ? root : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static SqlKind kindOf(String tag, Element e) {
        if ("CALLABLE".equalsIgnoreCase(e.getAttribute("statementType")) || tag.equals("procedure")) {
            return SqlKind.CALL;
        }
        return switch (tag) {
            case "select" -> SqlKind.SELECT;
            case "insert" -> SqlKind.INSERT;
            case "update" -> SqlKind.UPDATE;
            case "delete" -> SqlKind.DELETE;
            default -> null;   // iBatis <statement>: 분석기가 판단
        };
    }

    // ----------------------------------------------------------------- render

    private static final class Ctx {
        final Map<String, Fragment> local;
        final Deque<String> includeStack = new ArrayDeque<>();
        /** 지금 펼치고 있는 조각의 namespace (맨 아래는 쿼리가 있는 파일) */
        final Deque<String> nsStack = new ArrayDeque<>();
        /** include 의 property 값 (안쪽 include 가 위) */
        final Deque<Map<String, String>> props = new ArrayDeque<>();
        /** 이번 렌더링에서 고를 choose 분기 번호 */
        final int branch;
        int maxBranches = 1;
        boolean dynamic;

        Ctx(Map<String, Fragment> local, String ns, int branch) {
            this.local = local;
            this.nsStack.push(ns);
            this.branch = branch;
        }

        String prop(String name) {
            for (Map<String, String> m : props) {
                String v = m.get(name);
                if (v != null) return v;
            }
            return null;
        }

        /** ${이름} 을 include property 값으로 바꾼다. 값이 없으면 그대로 둔다 */
        String substitute(String text) {
            if (props.isEmpty() || !text.contains("${")) return text;
            Matcher m = PROP.matcher(text);
            StringBuilder sb = new StringBuilder();
            while (m.find()) {
                String v = prop(m.group(1).strip());
                m.appendReplacement(sb, Matcher.quoteReplacement(v != null ? v : m.group()));
            }
            m.appendTail(sb);
            return sb.toString();
        }
    }

    private String render(Node node, Ctx ctx) {
        StringBuilder sb = new StringBuilder();
        NodeList kids = node.getChildNodes();
        for (int i = 0; i < kids.getLength(); i++) {
            Node k = kids.item(i);
            switch (k.getNodeType()) {
                case Node.TEXT_NODE, Node.CDATA_SECTION_NODE -> sb.append(ctx.substitute(k.getNodeValue()));
                case Node.ELEMENT_NODE -> sb.append(' ').append(renderElement((Element) k, ctx)).append(' ');
                default -> { }
            }
        }
        return sb.toString();
    }

    private String renderElement(Element e, Ctx ctx) {
        String tag = e.getTagName();
        String prepend = e.getAttribute("prepend");   // iBatis
        String body;
        switch (tag) {
            case "include" -> body = include(e, ctx);
            case "choose" -> body = choose(e, ctx);
            case "where" -> {
                String c = stripLeadingAndOr(render(e, ctx));
                body = c.isBlank() ? "" : " WHERE " + c;
            }
            case "set" -> {
                String c = render(e, ctx).strip();
                while (c.endsWith(",")) c = c.substring(0, c.length() - 1).strip();
                body = c.isBlank() ? "" : " SET " + c;
            }
            case "trim" -> body = trim(e, render(e, ctx));
            case "foreach", "iterate" -> body = e.getAttribute("open") + render(e, ctx) + e.getAttribute("close");
            case "dynamic" -> body = stripLeadingAndOr(render(e, ctx));
            case "bind", "selectKey", "property" -> body = "";
            default -> body = render(e, ctx);   // if, when, otherwise, isNotEmpty ...
        }
        if (!prepend.isEmpty() && !body.isBlank()) body = " " + prepend + " " + body;
        return body;
    }

    private String include(Element e, Ctx ctx) {
        String ref = ctx.substitute(e.getAttribute("refid")).strip();
        if (ref.contains("${")) {
            ctx.dynamic = true;
            return "";
        }
        Fragment frag = findFragment(ref, ctx);
        String key = frag == null ? ref : frag.namespace() + "." + frag.element().getAttribute("id");
        if (frag == null || ctx.includeStack.contains(key)) {
            ctx.dynamic = true;   // 찾을 수 없는 조각 또는 순환 참조
            return "";
        }
        // <include refid="x"><property name="alias" value="e"/></include>
        Map<String, String> p = new HashMap<>();
        for (Element c : children(e)) {
            if (c.getTagName().equals("property") && !c.getAttribute("name").isEmpty()) {
                p.put(c.getAttribute("name"), ctx.substitute(c.getAttribute("value")));
            }
        }
        ctx.includeStack.push(key);
        ctx.nsStack.push(frag.namespace());
        ctx.props.push(p);
        try {
            return render(frag.element(), ctx);
        } finally {
            ctx.props.pop();
            ctx.nsStack.pop();
            ctx.includeStack.pop();
        }
    }

    /**
     * 조각 찾는 순서: 지금 namespace 의 id → 같은 파일 → 전체 이름(ns.id) → 쿼리 파일 namespace 의 id
     * → id 만으로 프로젝트 전체에서 하나뿐일 때.
     */
    private Fragment findFragment(String ref, Ctx ctx) {
        String cur = ctx.nsStack.peek();
        Fragment f = null;
        if (cur != null && !cur.isEmpty()) f = global.get(cur + "." + ref);
        if (f == null && ctx.nsStack.size() == 1) f = ctx.local.get(ref);
        if (f == null) f = global.get(ref);
        if (f == null) f = ctx.local.get(ref);
        if (f == null) {
            String base = ctx.nsStack.peekLast();
            if (base != null && !base.isEmpty()) f = global.get(base + "." + ref);
        }
        if (f == null) {
            List<Fragment> same = byId.get(ref);
            if (same != null && same.size() == 1) f = same.get(0);
        }
        return f;
    }

    /** ctx.branch 번째 분기 (when 들 다음 otherwise 순서). 분기가 그보다 적으면 마지막 분기 (안쪽 choose 까지 닿도록) */
    private String choose(Element e, Ctx ctx) {
        List<Element> branches = new ArrayList<>();
        Element otherwise = null;
        for (Element c : children(e)) {
            if (c.getTagName().equals("when")) branches.add(c);
            else if (c.getTagName().equals("otherwise")) otherwise = c;
        }
        if (otherwise != null) branches.add(otherwise);
        if (branches.isEmpty()) return "";
        ctx.maxBranches = Math.max(ctx.maxBranches, branches.size());
        int i = Math.min(ctx.branch, branches.size() - 1);
        return render(branches.get(i), ctx);
    }

    private static String trim(Element e, String content) {
        String c = content.strip();
        String po = e.getAttribute("prefixOverrides");
        if (!po.isEmpty()) {
            for (String o : po.split("\\|")) {
                String t = o.strip();
                if (!t.isEmpty() && c.toUpperCase(Locale.ROOT).startsWith(t.toUpperCase(Locale.ROOT))) {
                    c = c.substring(t.length()).strip();
                    break;
                }
            }
        }
        String so = e.getAttribute("suffixOverrides");
        if (!so.isEmpty()) {
            for (String o : so.split("\\|")) {
                String t = o.strip();
                if (!t.isEmpty() && c.toUpperCase(Locale.ROOT).endsWith(t.toUpperCase(Locale.ROOT))) {
                    c = c.substring(0, c.length() - t.length()).strip();
                    break;
                }
            }
        }
        if (c.isBlank()) return "";
        return " " + e.getAttribute("prefix") + " " + c + " " + e.getAttribute("suffix") + " ";
    }

    private static String stripLeadingAndOr(String s) {
        Matcher m = LEADING_ANDOR.matcher(s);
        return m.find() ? s.substring(m.end()).strip() : s.strip();
    }

    private static String clean(String sql) {
        return sql.replaceAll("\\s+", " ").strip();
    }

    // ----------------------------------------------------------------- utils

    private static Document parse(String content) throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setNamespaceAware(false);
        f.setValidating(false);
        f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        f.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        f.setFeature("http://xml.org/sax/features/external-general-entities", false);
        f.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        f.setXIncludeAware(false);
        f.setExpandEntityReferences(false);
        DocumentBuilder b = f.newDocumentBuilder();
        b.setErrorHandler(null);
        return b.parse(new InputSource(new StringReader(content)));
    }

    private static List<Element> children(Node n) {
        List<Element> r = new ArrayList<>();
        NodeList kids = n.getChildNodes();
        for (int i = 0; i < kids.getLength(); i++) {
            if (kids.item(i) instanceof Element e) r.add(e);
        }
        return r;
    }

    /** DOM 은 줄 번호를 주지 않으므로 원문에서 태그+id 를 찾아 계산한다. */
    private static int lineOf(String content, String tag, String id) {
        Matcher m = Pattern.compile("<" + tag + "\\b[^>]*\\bid\\s*=\\s*[\"']" + Pattern.quote(id) + "[\"']")
                .matcher(content);
        if (!m.find()) return 0;
        int line = 1;
        for (int i = 0; i < m.start(); i++) if (content.charAt(i) == '\n') line++;
        return line;
    }
}
