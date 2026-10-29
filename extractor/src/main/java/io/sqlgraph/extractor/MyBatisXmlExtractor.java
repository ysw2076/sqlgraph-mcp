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
 *   <li>choose: 첫 번째 when (없으면 otherwise) 만 포함</li>
 *   <li>where / set / trim: 앞뒤 AND, OR, 콤마 정리</li>
 *   <li>foreach / iterate: open + 내용 1회 + close</li>
 *   <li>include: 같은 파일의 sql 조각을 펼침 (다른 파일 참조는 dynamic 처리)</li>
 *   <li>#{..} → ?, ${..} → 동적 표시 (dynamic = true)</li>
 * </ul>
 */
public final class MyBatisXmlExtractor implements SqlExtractor {

    private static final Set<String> STATEMENT_TAGS = Set.of(
            "select", "insert", "update", "delete", "statement", "procedure");
    private static final Pattern LEADING_ANDOR = Pattern.compile("(?is)^\\s*(AND|OR)\\b");
    private static final Pattern DOLLAR = Pattern.compile("\\$\\{[^}]*}|\\$[A-Za-z_][\\w.\\[\\]]*\\$");

    @Override
    public String name() {
        return "mybatis-xml";
    }

    @Override
    public boolean supports(String relativePath) {
        return relativePath.toLowerCase(Locale.ROOT).endsWith(".xml");
    }

    @Override
    public List<ExtractedSql> extract(String relativePath, String content) {
        // 빠른 필터: MyBatis/iBatis 파일이 아니면 DOM 파싱하지 않는다
        if (!content.contains("<mapper") && !content.contains("<sqlMap")) return List.of();
        Document doc;
        try {
            doc = parse(content);
        } catch (Exception e) {
            return List.of();
        }
        Element root = doc.getDocumentElement();
        String rootName = root.getTagName();
        if (!rootName.equals("mapper") && !rootName.equals("sqlMap")) return List.of();
        String ns = root.getAttribute("namespace");

        // sql 조각 수집
        Map<String, Element> fragments = new HashMap<>();
        for (Element e : children(root)) {
            if (e.getTagName().equals("sql") && !e.getAttribute("id").isEmpty()) {
                String id = e.getAttribute("id");
                fragments.put(id, e);
                if (!ns.isEmpty()) fragments.put(ns + "." + id, e);
            }
        }

        List<ExtractedSql> out = new ArrayList<>();
        for (Element e : children(root)) {
            String tag = e.getTagName();
            if (!STATEMENT_TAGS.contains(tag)) continue;
            String id = e.getAttribute("id");
            if (id.isEmpty()) continue;
            Ctx ctx = new Ctx(fragments);
            String sql = clean(render(e, ctx));
            if (sql.isBlank()) continue;
            boolean dynamic = ctx.dynamic || DOLLAR.matcher(sql).find();
            String owner = ns.isEmpty() ? id : ns + "." + id;
            int line = lineOf(content, tag, id);
            out.add(new ExtractedSql(sql, owner, kindOf(tag, e), new SourceLocation(relativePath, line), dynamic));
        }
        return out;
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
        final Map<String, Element> fragments;
        final Deque<String> includeStack = new ArrayDeque<>();
        boolean dynamic;

        Ctx(Map<String, Element> fragments) {
            this.fragments = fragments;
        }
    }

    private String render(Node node, Ctx ctx) {
        StringBuilder sb = new StringBuilder();
        NodeList kids = node.getChildNodes();
        for (int i = 0; i < kids.getLength(); i++) {
            Node k = kids.item(i);
            switch (k.getNodeType()) {
                case Node.TEXT_NODE, Node.CDATA_SECTION_NODE -> sb.append(k.getNodeValue());
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
        String ref = e.getAttribute("refid");
        if (ref.contains("${")) {
            ctx.dynamic = true;
            return "";
        }
        Element frag = ctx.fragments.get(ref);
        if (frag == null || ctx.includeStack.contains(ref)) {
            ctx.dynamic = true;   // 다른 파일의 조각 또는 순환 참조
            return "";
        }
        ctx.includeStack.push(ref);
        try {
            return render(frag, ctx);
        } finally {
            ctx.includeStack.pop();
        }
    }

    private String choose(Element e, Ctx ctx) {
        Element otherwise = null;
        for (Element c : children(e)) {
            if (c.getTagName().equals("when")) return render(c, ctx);
            if (c.getTagName().equals("otherwise")) otherwise = c;
        }
        return otherwise == null ? "" : render(otherwise, ctx);
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
