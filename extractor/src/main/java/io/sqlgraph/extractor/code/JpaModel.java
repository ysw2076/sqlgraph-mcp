package io.sqlgraph.extractor.code;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * JPA 엔티티(@Entity, @Table, @Column, @JoinColumn …) 정보를 모아 JPQL 을 실제 테이블·컬럼 SQL 로 바꾼다.
 *
 * <p>목적은 "어떤 테이블·컬럼을 읽고 쓰는가"를 분석기에 넘기는 것이라, 실행 가능한 SQL 을 만들지는 않는다.
 * 이름을 지정하지 않은 엔티티·필드는 Spring Boot 기본 규칙(camelCase → snake_case)으로,
 * {@code PhysicalNamingStrategyStandardImpl} 설정이 있으면 이름 그대로 매핑한다.
 */
final class JpaModel {

    /**
     * @param column     컬럼명 (관계 필드는 조인 컬럼, 컬렉션 관계는 null)
     * @param target     관계 대상 엔티티 클래스명 (일반 필드는 null)
     * @param collection OneToMany / ManyToMany
     * @param mappedBy   반대편 필드명
     * @param embeddable 임베디드 타입 클래스명
     * @param id         @Id / @EmbeddedId
     */
    record Field(String name, String column, String target, boolean collection, String mappedBy,
                 String embeddable, boolean id) {}

    static final class Entity {
        final String className;
        String entityName;
        String table;
        String superClass;
        final Map<String, Field> fields = new LinkedHashMap<>();

        Entity(String className) {
            this.className = className;
        }
    }

    private final Map<String, Entity> byClass = new HashMap<>();
    private final Map<String, Entity> byEntityName = new HashMap<>();
    private boolean physicalAsIs;

    boolean isEmpty() {
        return byEntityName.isEmpty();
    }

    void usePhysicalNamesAsIs() {
        physicalAsIs = true;
    }

    // ================================================================= 수집

    private static final Pattern TYPE_ANN = Pattern.compile(
            "@(?:jakarta\\.persistence\\.|javax\\.persistence\\.)?(Entity|Embeddable|MappedSuperclass)\\b(\\s*\\(([^)]*)\\))?");
    private static final Pattern CLASS_DECL = Pattern.compile(
            "\\b(?:class|record)\\s+(\\w+)(?:\\s*<[^>{]*>)?([^{]*)\\{");
    private static final Pattern EXTENDS = Pattern.compile("(?:\\bextends\\s+|\\)\\s*:\\s*|^\\s*:\\s*)([\\w.]+)");
    private static final Pattern NAME_ARG = Pattern.compile("\\bname\\s*=\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");
    private static final Pattern SCHEMA_ARG = Pattern.compile("\\bschema\\s*=\\s*\"([^\"]*)\"");
    private static final Pattern MAPPED_BY = Pattern.compile("\\bmappedBy\\s*=\\s*\"([^\"]*)\"");
    private static final Pattern FIRST_STRING = Pattern.compile("^\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");
    private static final Pattern ANNOTATION = Pattern.compile("@([\\w.]+)(\\s*\\((?:[^()]|\\([^()]*\\))*\\))?");
    /** Java 필드: [수식어] 타입 이름 [= 초기값]; */
    private static final Pattern JAVA_FIELD = Pattern.compile(
            "^((?:@[\\w.]+(?:\\s*\\((?:[^()]|\\([^()]*\\))*\\))?\\s*)*)((?:(?:private|protected|public|static|final|transient|volatile)\\s+)*)"
                    + "([\\w.]+(?:\\s*<[^;=]*?>)?(?:\\[])?)\\s+(\\w+)\\s*(?:=[^;]*)?$", Pattern.DOTALL);
    /** Kotlin 프로퍼티 / 주 생성자 파라미터 */
    private static final Pattern KOTLIN_PROP = Pattern.compile(
            "((?:@[\\w.:]+(?:\\s*\\((?:[^()]|\\([^()]*\\))*\\))?\\s*)*)(?:(?:private|protected|public|internal|lateinit|override|open)\\s+)*"
                    + "(?:var|val)\\s+(\\w+)\\s*:\\s*([\\w.]+(?:\\s*<[^>]*>)?\\??)");

    /** 엔티티·임베디드·상위 클래스 선언이 있는 소스를 읽는다 (Java / Kotlin) */
    void addSource(String content, boolean kotlin) {
        if (!content.contains("@Entity") && !content.contains("@Embeddable") && !content.contains("@MappedSuperclass")) return;
        Matcher m = TYPE_ANN.matcher(content);
        while (m.find()) {
            Matcher cd = CLASS_DECL.matcher(content);
            if (!cd.find(m.end())) continue;
            // 어노테이션과 클래스 선언 사이에 다른 클래스 본문이 끼면 안 됨
            if (content.substring(m.end(), cd.start()).contains("{")) continue;
            String cls = cd.group(1);
            Entity e = byClass.computeIfAbsent(cls, Entity::new);
            String header = content.substring(m.start(), cd.start());
            String kind = m.group(1);
            if (kind.equals("Entity")) {
                String en = m.group(3) == null ? null : arg(NAME_ARG, m.group(3));
                e.entityName = en != null ? en : cls;
                byEntityName.put(e.entityName, e);
            }
            Matcher tbl = Pattern.compile("@(?:[\\w.]*\\.)?Table\\s*\\(((?:[^()]|\\([^()]*\\))*)\\)").matcher(header);
            if (tbl.find()) {
                String n = arg(NAME_ARG, tbl.group(1));
                String sch = arg(SCHEMA_ARG, tbl.group(1));
                if (n != null) e.table = (sch != null && !sch.isBlank() ? sch + "." : "") + unquoteName(n);
            }
            Matcher ext = EXTENDS.matcher(cd.group(2));
            if (ext.find()) e.superClass = simple(ext.group(1));

            int bodyStart = cd.end();
            int bodyEnd = matchBrace(content, bodyStart - 1);
            String body = content.substring(bodyStart, Math.max(bodyStart, bodyEnd));
            if (kotlin) {
                // 주 생성자 (class Emp(@Id val empNo: String, ...))
                String decl = content.substring(cd.start(), bodyStart);
                int p = decl.indexOf('(');
                if (p >= 0) {
                    int q = matchParen(decl, p);
                    readKotlinProps(decl.substring(p + 1, Math.max(p + 1, q)), e);
                }
                readKotlinProps(topLevel(body, false), e);
            } else {
                for (String seg : topLevel(body, true).split(";")) readJavaField(seg.strip(), e);
            }
        }
        // Kotlin data class 처럼 본문이 없는 엔티티
        if (kotlin) {
            Matcher k = Pattern.compile("@Entity[\\s\\S]*?\\bclass\\s+(\\w+)\\s*\\(").matcher(content);
            while (k.find()) {
                Entity e = byClass.get(k.group(1));
                if (e == null || !e.fields.isEmpty()) continue;
                int p = k.end() - 1;
                readKotlinProps(content.substring(p + 1, Math.max(p + 1, matchParen(content, p))), e);
            }
        }
    }

    private void readJavaField(String seg, Entity e) {
        if (seg.isEmpty()) return;
        Matcher f = JAVA_FIELD.matcher(seg);
        if (!f.matches()) return;
        String mods = f.group(2);
        if (mods.contains("static") || mods.contains("transient")) return;
        addField(e, f.group(1), f.group(4), f.group(3));
    }

    private void readKotlinProps(String text, Entity e) {
        Matcher p = KOTLIN_PROP.matcher(text);
        while (p.find()) addField(e, p.group(1), p.group(2), p.group(3));
    }

    private void addField(Entity e, String annotations, String name, String type) {
        Map<String, String> anns = new HashMap<>();
        Matcher a = ANNOTATION.matcher(annotations == null ? "" : annotations);
        while (a.find()) {
            String an = simple(a.group(1).replaceFirst("^(field|get|param):", ""));
            String args = a.group(2) == null ? "" : a.group(2).strip();
            if (args.startsWith("(")) args = args.substring(1, args.length() - 1);
            anns.put(an, args);
        }
        if (anns.containsKey("Transient")) return;
        boolean id = anns.containsKey("Id") || anns.containsKey("EmbeddedId");
        String t = type.replaceAll("\\s+", "").replace("?", "");
        boolean collection = anns.containsKey("OneToMany") || anns.containsKey("ManyToMany")
                || anns.containsKey("ElementCollection");
        boolean relation = collection || anns.containsKey("ManyToOne") || anns.containsKey("OneToOne");
        if (anns.containsKey("ElementCollection")) return;
        String target = null;
        String mappedBy = null;
        String column = null;
        String embeddable = null;
        if (relation) {
            target = collection ? genericArg(t) : simple(t.replaceAll("<.*", ""));
            for (String k : List.of("OneToMany", "ManyToMany", "OneToOne", "ManyToOne")) {
                if (anns.containsKey(k)) {
                    String mb = arg(MAPPED_BY, anns.get(k));
                    if (mb != null) mappedBy = mb;
                    Matcher te = Pattern.compile("targetEntity\\s*=\\s*([\\w.]+)").matcher(anns.get(k));
                    if (te.find()) target = simple(te.group(1).replaceAll("\\.(class|java)$|::class", ""));
                }
            }
            if (!collection && mappedBy == null) {
                String jc = anns.containsKey("JoinColumn") ? columnName(anns.get("JoinColumn")) : null;
                column = jc;   // null 이면 번역 시 기본 규칙(필드_대상PK)
            }
        } else if (anns.containsKey("Embedded") || anns.containsKey("EmbeddedId")) {
            embeddable = simple(t.replaceAll("<.*", ""));
        } else {
            String cn = anns.containsKey("Column") ? columnName(anns.get("Column")) : null;
            column = cn != null ? cn : implicit(name);
        }
        e.fields.put(name, new Field(name, column, target, collection, mappedBy, embeddable, id));
    }

    /** @Column("X") / @Column(name = "X") */
    private static String columnName(String args) {
        if (args == null) return null;
        String n = arg(NAME_ARG, args);
        if (n == null) {
            Matcher s = FIRST_STRING.matcher(args);
            if (s.find() && !args.contains("=")) n = s.group(1);
        }
        return n == null || n.isBlank() ? null : unquoteName(n);
    }

    private static String arg(Pattern p, String args) {
        if (args == null) return null;
        Matcher m = p.matcher(args);
        return m.find() ? m.group(1) : null;
    }

    private static String unquoteName(String n) {
        return n.replace("\\\"", "").replace("\"", "").replace("`", "").replace("[", "").replace("]", "").strip();
    }

    private static String simple(String n) {
        String s = n.strip();
        int d = s.lastIndexOf('.');
        return d >= 0 ? s.substring(d + 1) : s;
    }

    private static String genericArg(String t) {
        int lt = t.indexOf('<');
        int gt = t.lastIndexOf('>');
        if (lt < 0 || gt < lt) return simple(t);
        String inner = t.substring(lt + 1, gt);
        String[] parts = inner.split(",");
        return simple(parts[parts.length - 1].replace("?extends", "").replace("out", "").strip());
    }

    String implicit(String javaName) {
        return physicalAsIs ? javaName : snake(javaName);
    }

    static String snake(String s) {
        StringBuilder sb = new StringBuilder();
        for (int k = 0; k < s.length(); k++) {
            char c = s.charAt(k);
            if (Character.isUpperCase(c) && k > 0 && (Character.isLowerCase(s.charAt(k - 1))
                    || (k + 1 < s.length() && Character.isLowerCase(s.charAt(k + 1)) && Character.isUpperCase(s.charAt(k - 1))))) {
                sb.append('_');
            }
            sb.append(Character.toLowerCase(c));
        }
        return sb.toString();
    }

    /** 클래스 본문에서 메서드·중첩 블록을 뺀 최상위 선언부. javaStyle 이면 블록 자리에 ; 를 넣어 선언을 끊는다 */
    private static String topLevel(String body, boolean javaStyle) {
        StringBuilder sb = new StringBuilder();
        int depth = 0;
        for (int k = 0; k < body.length(); k++) {
            char c = body.charAt(k);
            if (c == '"') {
                int e = skipString(body, k);
                if (depth == 0) sb.append(body, k, e);
                k = e - 1;
                continue;
            }
            if (c == '/' && k + 1 < body.length() && body.charAt(k + 1) == '/') {
                int e = body.indexOf('\n', k);
                k = e < 0 ? body.length() : e;
                if (depth == 0) sb.append('\n');
                continue;
            }
            if (c == '/' && k + 1 < body.length() && body.charAt(k + 1) == '*') {
                int e = body.indexOf("*/", k + 2);
                k = e < 0 ? body.length() : e + 1;
                continue;
            }
            if (c == '{') {
                if (depth == 0 && javaStyle) sb.append(';');
                depth++;
                continue;
            }
            if (c == '}') {
                depth = Math.max(0, depth - 1);
                if (depth == 0) sb.append(javaStyle ? ";" : "\n");
                continue;
            }
            if (depth == 0) sb.append(c);
        }
        return sb.toString();
    }

    private static int skipString(String s, int k) {
        if (s.startsWith("\"\"\"", k)) {
            int e = s.indexOf("\"\"\"", k + 3);
            return e < 0 ? s.length() : e + 3;
        }
        int e = k + 1;
        while (e < s.length() && s.charAt(e) != '"') {
            if (s.charAt(e) == '\\') e++;
            e++;
        }
        return Math.min(s.length(), e + 1);
    }

    private static int matchBrace(String s, int open) {
        int depth = 0;
        for (int k = open; k < s.length(); k++) {
            char c = s.charAt(k);
            if (c == '"') {
                k = skipString(s, k) - 1;
                continue;
            }
            if (c == '{') depth++;
            else if (c == '}' && --depth == 0) return k;
        }
        return s.length();
    }

    static int matchParen(String s, int open) {
        int depth = 0;
        for (int k = open; k < s.length(); k++) {
            char c = s.charAt(k);
            if (c == '"' || c == '\'') {
                char q = c;
                k++;
                while (k < s.length() && s.charAt(k) != q) {
                    if (s.charAt(k) == '\\') k++;
                    k++;
                }
                continue;
            }
            if (c == '(') depth++;
            else if (c == ')' && --depth == 0) return k;
        }
        return s.length();
    }

    // ================================================================= 조회

    Entity entity(String jpqlName) {
        Entity e = byEntityName.get(jpqlName);
        if (e != null) return e;
        for (var en : byEntityName.entrySet()) {
            if (en.getKey().equalsIgnoreCase(jpqlName)) return en.getValue();
        }
        return null;
    }

    boolean isEntityName(String name) {
        return byEntityName.containsKey(name);
    }

    String table(Entity e) {
        // JPA 기본 테이블명은 엔티티명 (@Entity(name=..) 이 없으면 클래스명)
        return e.table != null ? e.table : implicit(e.entityName != null ? e.entityName : e.className);
    }

    Field field(Entity e, String name) {
        for (Entity cur = e; cur != null; cur = cur.superClass == null ? null : byClass.get(cur.superClass)) {
            Field f = cur.fields.get(name);
            if (f != null) return f;
            if (cur.superClass != null && cur.superClass.equals(cur.className)) break;
        }
        return null;
    }

    Field idField(Entity e) {
        for (Entity cur = e; cur != null; cur = cur.superClass == null ? null : byClass.get(cur.superClass)) {
            for (Field f : cur.fields.values()) if (f.id()) return f;
            if (cur.superClass != null && cur.superClass.equals(cur.className)) break;
        }
        return null;
    }

    /** 단일 컬럼 PK 컬럼명 (복합키·모르면 null) */
    String idColumn(Entity e) {
        Field f = idField(e);
        return f == null || f.embeddable() != null ? null : f.column();
    }

    /** ManyToOne / OneToOne 소유측의 조인 컬럼 (지정 안 됐으면 기본 규칙) */
    String joinColumn(Field f) {
        if (f.column() != null) return f.column();
        if (f.target() == null || f.collection() || f.mappedBy() != null) return null;
        Entity t = byClass.get(f.target());
        String pk = t == null ? null : idColumn(t);
        return implicit(f.name()) + "_" + (pk != null ? pk : "id");
    }

    Entity byClass(String cls) {
        return cls == null ? null : byClass.get(cls);
    }

    // ================================================================ JPQL → SQL

    private static final Set<String> KEYWORDS = Set.of(
            "WHERE", "JOIN", "LEFT", "RIGHT", "INNER", "OUTER", "CROSS", "FULL", "ON", "SET", "ORDER", "GROUP",
            "HAVING", "UNION", "FETCH", "AND", "OR", "NOT", "AS", "SELECT", "FROM", "BY", "IN", "IS", "NULL",
            "BETWEEN", "LIKE", "EXISTS", "CASE", "WHEN", "THEN", "ELSE", "END", "DISTINCT", "VALUES", "INTO",
            "WITH", "LIMIT", "OFFSET", "ASC", "DESC", "TREAT", "KEY", "VALUE", "ENTRY", "MEMBER", "OF", "EMPTY",
            "NEW", "UPDATE", "DELETE", "TRUE", "FALSE", "COUNT", "SUM", "AVG", "MIN", "MAX", "LOWER", "UPPER");

    private record Repl(int start, int end, String text) {}

    private record Decl(String alias, Entity entity) {}

    /**
     * JPQL(HQL) 을 테이블·컬럼 이름으로 바꾼 SQL. 아는 엔티티가 하나도 없으면 null.
     */
    String translate(String jpql) {
        String s = jpql.replaceAll("\\?\\d+", "?");
        s = s.replaceAll("(?i)\\bJOIN\\s+FETCH\\b", "JOIN");
        s = removeConstructorExpressions(s);
        // HQL: SELECT 없이 FROM 으로 시작
        if (s.stripLeading().regionMatches(true, 0, "FROM ", 0, 5)) s = "SELECT * " + s.stripLeading();

        List<int[]> literals = stringRanges(s);
        Map<String, Decl> aliases = new LinkedHashMap<>();
        List<Repl> repl = new ArrayList<>();
        List<Decl> unaliased = new ArrayList<>();
        boolean known = false;

        // 1) 선언: FROM/JOIN/UPDATE/INTO 뒤의 엔티티 (FROM 뒤 콤마 목록 포함)
        Matcher d = Pattern.compile("(?i)\\b(FROM|JOIN|UPDATE|INTO)\\s+").matcher(s);
        int searchFrom = 0;
        while (d.find(searchFrom)) {
            searchFrom = d.end();
            if (inRanges(d.start(), literals)) continue;
            boolean isFrom = d.group(1).equalsIgnoreCase("FROM");
            int pos = d.end();
            while (true) {
                Matcher item = Pattern.compile("\\G([A-Za-z_][\\w.$]*)(?:(\\s+(?:(?i:AS)\\s+)?)([A-Za-z_]\\w*))?").matcher(s);
                if (!item.find(pos)) break;
                String name = item.group(1);
                String alias = item.group(3);
                if (alias != null && KEYWORDS.contains(alias.toUpperCase(Locale.ROOT))) alias = null;
                int nameEnd = item.start(1) + name.length();
                int itemEnd = alias != null ? item.end(3) : nameEnd;
                if (name.contains(".")) {
                    // 경로 조인: JOIN e.dept d
                    String a0 = name.substring(0, name.indexOf('.'));
                    String fn = name.substring(name.indexOf('.') + 1);
                    Decl owner = aliases.get(a0);
                    Field f = owner == null ? null : field(owner.entity(), fn);
                    Entity target = f == null ? null : byClass.get(f.target());
                    if (target != null) {
                        known = true;
                        repl.add(new Repl(item.start(1), nameEnd, table(target)));
                        String al = alias != null ? alias : "j" + aliases.size();
                        if (alias == null) repl.add(new Repl(nameEnd, nameEnd, " " + al));
                        aliases.put(al, new Decl(al, target));
                        String on = joinCondition(owner.alias(), owner.entity(), f, al, target);
                        // 이미 ON 이 있으면 (JPA 2.1 JOIN ... ON) AND 로 이어 붙인다
                        Matcher hasOn = Pattern.compile("\\G\\s+(?i:ON)\\b").matcher(s);
                        if (hasOn.find(itemEnd)) {
                            repl.add(new Repl(hasOn.end(), hasOn.end(), " " + on + " AND"));
                        } else {
                            repl.add(new Repl(itemEnd, itemEnd, " ON " + on));
                        }
                    }
                } else {
                    Entity e = entity(name);
                    if (e != null) {
                        known = true;
                        repl.add(new Repl(item.start(1), nameEnd, table(e)));
                        if (alias != null) aliases.put(alias, new Decl(alias, e));
                        else unaliased.add(new Decl(null, e));
                    }
                }
                pos = itemEnd;
                if (!isFrom) break;
                Matcher comma = Pattern.compile("\\G\\s*,\\s*").matcher(s);
                if (!comma.find(pos)) break;
                pos = comma.end();
            }
        }
        if (!known) return null;

        List<int[]> covered = new ArrayList<>();
        for (Repl r : repl) covered.add(new int[]{r.start(), Math.max(r.end(), r.start() + 1)});

        // 2) 경로식: e.field / e.dept.name / e.id.empNo
        Matcher p = Pattern.compile("(?<![\\w.:$])([A-Za-z_]\\w*)((?:\\.[A-Za-z_]\\w*)+)").matcher(s);
        while (p.find()) {
            if (inRanges(p.start(), literals) || overlaps(p.start(), p.end(), covered)) continue;
            Decl dcl = aliases.get(p.group(1));
            if (dcl == null) continue;
            String[] segs = p.group(2).substring(1).split("\\.");
            String r = resolvePath(dcl.alias(), dcl.entity(), segs);
            if (r != null) repl.add(new Repl(p.start(), p.end(), r));
        }

        // 3) 별칭 단독: SELECT e → e.*, WHERE e = :x → e.PK
        if (!aliases.isEmpty()) {
            String names = String.join("|", aliases.keySet().stream().map(Pattern::quote).toList());
            Matcher b = Pattern.compile("(?<![\\w.:$])(" + names + ")(?![\\w.])").matcher(s);
            int[] selectList = selectListRange(s);
            while (b.find()) {
                if (inRanges(b.start(), literals) || overlaps(b.start(), b.end(), covered)) continue;
                if (followedByDeclarationAlias(s, b.start())) continue;
                Decl dcl = aliases.get(b.group(1));
                boolean inSelect = selectList != null && b.start() >= selectList[0] && b.end() <= selectList[1];
                if (inSelect && isWholeSelectItem(s, b.start(), b.end(), selectList)) {
                    repl.add(new Repl(b.start(), b.end(), b.group(1) + ".*"));
                } else {
                    String pk = idColumn(dcl.entity());
                    if (pk != null) repl.add(new Repl(b.start(), b.end(), b.group(1) + "." + pk));
                }
            }
        }

        // 4) 별칭 없는 단일 엔티티(UPDATE Emp SET name = :n): 맨 이름 필드를 컬럼으로
        if (aliases.isEmpty() && unaliased.size() == 1) {
            Entity e = unaliased.get(0).entity();
            Matcher w = Pattern.compile("(?<![\\w.:$])([A-Za-z_]\\w*)(?![\\w.(])").matcher(s);
            while (w.find()) {
                if (inRanges(w.start(), literals) || overlaps(w.start(), w.end(), covered)) continue;
                if (KEYWORDS.contains(w.group(1).toUpperCase(Locale.ROOT))) continue;
                Field f = field(e, w.group(1));
                if (f == null) continue;
                String col = f.target() != null ? joinColumn(f) : f.column();
                if (col != null) repl.add(new Repl(w.start(), w.end(), col));
            }
        }
        return apply(s, repl);
    }

    private String resolvePath(String alias, Entity e, String[] segs) {
        Field f = field(e, segs[0]);
        if (f == null) return null;
        if (f.embeddable() != null) {
            if (segs.length < 2) return null;
            Entity emb = byClass.get(f.embeddable());
            Field sub = emb == null ? null : emb.fields.get(segs[1]);
            return sub == null || sub.column() == null ? null : alias + "." + sub.column();
        }
        if (f.target() != null) {
            Entity t = byClass.get(f.target());
            if (segs.length == 1) {
                String jc = joinColumn(f);
                return jc == null ? null : alias + "." + jc;
            }
            if (t == null) return null;
            Field tf = field(t, segs[1]);
            if (tf == null) return null;
            if (tf.id() && !f.collection() && f.mappedBy() == null) {
                String jc = joinColumn(f);   // e.dept.id → e.dept_id (조인 없이 FK 로)
                if (jc != null) return alias + "." + jc;
            }
            String col = tf.target() != null ? joinColumn(tf) : tf.column();
            return col == null ? null : table(t) + "." + col;   // 암시적 조인: 테이블명으로 한정
        }
        return f.column() == null ? null : alias + "." + f.column();
    }

    /** 경로 조인의 ON 조건. 모르면 1=1 */
    private String joinCondition(String ownerAlias, Entity owner, Field f, String targetAlias, Entity target) {
        if (!f.collection() && f.mappedBy() == null) {
            String jc = joinColumn(f);
            String pk = idColumn(target);
            if (jc != null && pk != null) return ownerAlias + "." + jc + " = " + targetAlias + "." + pk;
        }
        if (f.mappedBy() != null) {
            Field back = field(target, f.mappedBy());
            String jc = back == null ? null : joinColumn(back);
            String pk = idColumn(owner);
            if (jc != null && pk != null) return targetAlias + "." + jc + " = " + ownerAlias + "." + pk;
        }
        return "1=1";
    }

    private static String removeConstructorExpressions(String s) {
        Matcher m = Pattern.compile("(?i)\\bNEW\\s+[\\w.$]+\\s*\\(").matcher(s);
        StringBuilder sb = new StringBuilder();
        int last = 0;
        while (m.find(last)) {
            int open = m.end() - 1;
            int close = matchParen(s, open);
            sb.append(s, last, m.start()).append(s, open + 1, Math.min(close, s.length()));
            last = Math.min(close + 1, s.length());
        }
        sb.append(s.substring(last));
        return sb.toString();
    }

    /** 첫 SELECT 와 같은 깊이의 FROM 사이 */
    private static int[] selectListRange(String s) {
        Matcher sel = Pattern.compile("(?i)^\\s*SELECT\\s+(?:DISTINCT\\s+)?").matcher(s);
        if (!sel.find()) return null;
        int depth = 0;
        for (int k = sel.end(); k < s.length(); k++) {
            char c = s.charAt(k);
            if (c == '(') depth++;
            else if (c == ')') depth--;
            else if (depth == 0 && s.regionMatches(true, k, "FROM", 0, 4)
                    && (k == 0 || !Character.isLetterOrDigit(s.charAt(k - 1)))
                    && (k + 4 >= s.length() || !Character.isLetterOrDigit(s.charAt(k + 4)))) {
                return new int[]{sel.end(), k};
            }
        }
        return null;
    }

    /** 선택 목록에서 콤마 사이에 별칭만 단독으로 있는지 (COUNT(e) 같은 함수 인자는 제외) */
    private static boolean isWholeSelectItem(String s, int start, int end, int[] range) {
        String before = s.substring(range[0], start).strip();
        String after = s.substring(end, range[1]).strip();
        boolean leftOk = before.isEmpty() || before.endsWith(",") || before.equalsIgnoreCase("DISTINCT");
        boolean rightOk = after.isEmpty() || after.startsWith(",");
        return leftOk && rightOk;
    }

    /** FROM Emp e 의 e 처럼 선언 자리면 true */
    private static boolean followedByDeclarationAlias(String s, int start) {
        String before = s.substring(0, start).stripTrailing();
        return before.matches("(?is).*\\b(FROM|JOIN|UPDATE|INTO|AS)\\s+[\\w.$]+$")
                || before.matches("(?is).*\\b(AS)$")
                || before.matches("(?is).*\\b(FROM|JOIN|UPDATE|INTO)\\s*[\\w.$]*\\s*,\\s*[\\w.$]+$");
    }

    private static List<int[]> stringRanges(String s) {
        List<int[]> r = new ArrayList<>();
        for (int k = 0; k < s.length(); k++) {
            if (s.charAt(k) == '\'') {
                int e = k + 1;
                while (e < s.length()) {
                    if (s.charAt(e) == '\'') {
                        if (e + 1 < s.length() && s.charAt(e + 1) == '\'') {
                            e += 2;
                            continue;
                        }
                        break;
                    }
                    e++;
                }
                r.add(new int[]{k, e});
                k = e;
            }
        }
        return r;
    }

    private static boolean inRanges(int pos, List<int[]> ranges) {
        for (int[] r : ranges) if (pos >= r[0] && pos <= r[1]) return true;
        return false;
    }

    private static boolean overlaps(int s, int e, List<int[]> ranges) {
        for (int[] r : ranges) if (s < r[1] && e > r[0]) return true;
        return false;
    }

    private static String apply(String s, List<Repl> repl) {
        List<Repl> sorted = new ArrayList<>(repl);
        sorted.sort((a, b) -> a.start() != b.start() ? Integer.compare(b.start(), a.start()) : Integer.compare(b.end(), a.end()));
        StringBuilder sb = new StringBuilder(s);
        int guard = Integer.MAX_VALUE;
        for (Repl r : sorted) {
            if (r.end() > guard) continue;   // 겹치는 치환은 앞선(바깥) 것만
            sb.replace(r.start(), r.end(), r.text());
            guard = r.start();
        }
        return sb.toString();
    }
}
