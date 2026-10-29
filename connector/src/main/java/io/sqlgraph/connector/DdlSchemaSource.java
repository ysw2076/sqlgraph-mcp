package io.sqlgraph.connector;

import io.sqlgraph.core.Names;
import io.sqlgraph.core.SqlScripts;
import io.sqlgraph.core.TextFiles;
import io.sqlgraph.core.model.DbColumn;
import io.sqlgraph.core.model.DbTable;
import io.sqlgraph.core.model.ForeignKey;
import io.sqlgraph.core.model.SchemaSnapshot;
import io.sqlgraph.core.spi.SchemaSource;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.create.table.ColDataType;
import net.sf.jsqlparser.statement.create.table.ColumnDefinition;
import net.sf.jsqlparser.statement.create.table.CreateTable;
import net.sf.jsqlparser.statement.create.table.ForeignKeyIndex;
import net.sf.jsqlparser.statement.create.table.Index;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * DDL 파일(CREATE TABLE, COMMENT ON, ALTER TABLE ... PRIMARY KEY/FOREIGN KEY)에서 스키마를 만든다.
 * DB 에 직접 접속할 수 없는 환경용. mysqldump, Oracle export DDL 모두 대상.
 * JSqlParser 로 파싱하고, 실패하면 단순 파서로 컬럼 목록만이라도 뽑는다.
 */
public final class DdlSchemaSource implements SchemaSource {

    private static final String ID = "(?:\"[^\"]+\"|`[^`]+`|\\[[^\\]]+]|[\\w$#\\p{L}]+)";
    private static final String QNAME = "(" + ID + "(?:\\s*\\.\\s*" + ID + ")?)";
    private static final String STR = "'((?:[^']|'')*)'";

    private static final Pattern CREATE_TABLE = Pattern.compile(
            "(?is)^CREATE\\s+(?:OR\\s+REPLACE\\s+)?(?:GLOBAL\\s+|LOCAL\\s+)?(?:TEMPORARY\\s+|TEMP\\s+)?TABLE\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?"
                    + QNAME + "\\s*\\((.*)\\)(.*)$");
    private static final Pattern CREATE_VIEW = Pattern.compile(
            "(?is)^CREATE\\s+(?:OR\\s+REPLACE\\s+)?(?:FORCE\\s+|NOFORCE\\s+)?(?:ALGORITHM\\s*=\\s*\\w+\\s+)?"
                    + "(?:DEFINER\\s*=\\s*\\S+\\s+)?(?:SQL\\s+SECURITY\\s+\\w+\\s+)?(?:MATERIALIZED\\s+)?VIEW\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?"
                    + QNAME);
    private static final Pattern COMMENT_TABLE = Pattern.compile(
            "(?is)^COMMENT\\s+ON\\s+TABLE\\s+" + QNAME + "\\s+IS\\s+" + STR);
    private static final Pattern COMMENT_COLUMN = Pattern.compile(
            "(?is)^COMMENT\\s+ON\\s+COLUMN\\s+(" + ID + "(?:\\s*\\.\\s*" + ID + "){1,2})\\s+IS\\s+" + STR);
    private static final Pattern ALTER_PK = Pattern.compile(
            "(?is)^ALTER\\s+TABLE\\s+(?:ONLY\\s+)?" + QNAME + "\\s+ADD\\s+(?:CONSTRAINT\\s+" + ID + "\\s+)?PRIMARY\\s+KEY\\s*(?:\\w+\\s*)?\\(([^)]*)\\)");
    private static final Pattern ALTER_FK = Pattern.compile(
            "(?is)^ALTER\\s+TABLE\\s+(?:ONLY\\s+)?" + QNAME + "\\s+ADD\\s+(?:CONSTRAINT\\s+" + ID + "\\s+)?FOREIGN\\s+KEY\\s*\\(([^)]*)\\)\\s*REFERENCES\\s+"
                    + QNAME + "\\s*\\(([^)]*)\\)");
    private static final Pattern MYSQL_TABLE_COMMENT = Pattern.compile("(?is)\\bCOMMENT\\s*=?\\s*" + STR);
    private static final Pattern COLUMN_COMMENT = Pattern.compile("(?is)\\bCOMMENT\\s+" + STR);
    private static final Pattern INLINE_FK = Pattern.compile(
            "(?is)FOREIGN\\s+KEY\\s*\\(([^)]*)\\)\\s*REFERENCES\\s+" + QNAME + "\\s*\\(([^)]*)\\)");
    private static final Pattern INLINE_PK = Pattern.compile("(?is)PRIMARY\\s+KEY\\s*(?:\\w+\\s*)?\\(([^)]*)\\)");
    private static final Set<String> CONSTRAINT_WORDS = Set.of(
            "CONSTRAINT", "PRIMARY", "UNIQUE", "KEY", "INDEX", "FOREIGN", "CHECK", "FULLTEXT", "SPATIAL",
            "PERIOD", "EXCLUDE", "LIKE");

    private final String id;
    private final List<Path> files;
    private final String product;

    /**
     * @param files   DDL 파일 목록 (glob 은 호출측에서 펼친 상태)
     * @param dialect 표시용 (oracle, mariadb ...)
     */
    public DdlSchemaSource(String id, List<Path> files, String dialect) {
        this.id = id;
        this.files = files;
        this.product = "DDL" + (dialect == null ? "" : " (" + dialect + ")");
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public SchemaSnapshot load() throws Exception {
        Model model = new Model();
        List<String> warnings = new ArrayList<>();
        if (files.isEmpty()) warnings.add("DDL 파일이 없습니다");
        for (Path f : files) {
            String text = TextFiles.read(f);
            for (SqlScripts.Chunk chunk : SqlScripts.split(text)) {
                String stmt = chunk.sql();
                try {
                    apply(stmt, model, warnings);
                } catch (Exception e) {
                    warnings.add(f.getFileName() + ": " + e.getMessage());
                }
            }
        }
        return model.toSnapshot(id, product, warnings);
    }

    // ------------------------------------------------------------------ apply

    void apply(String stmt, Model m, List<String> warnings) {
        String upper = stmt.toUpperCase(Locale.ROOT);
        Matcher x;
        if (upper.startsWith("CREATE") && upper.matches("(?s)^CREATE\\s+(OR\\s+REPLACE\\s+)?(GLOBAL\\s+|LOCAL\\s+)?(TEMPORARY\\s+|TEMP\\s+)?TABLE\\b.*")) {
            if (!createTableWithParser(stmt, m)) {
                if (!createTableFallback(stmt, m)) warnings.add("CREATE TABLE 해석 실패: " + head(stmt));
            }
        } else if ((x = CREATE_VIEW.matcher(stmt)).find()) {
            String[] sn = splitName(x.group(1));
            m.table(sn[0], sn[1]).kind = "VIEW";
        } else if ((x = COMMENT_TABLE.matcher(stmt)).find()) {
            String[] sn = splitName(x.group(1));
            m.table(sn[0], sn[1]).comment = unescape(x.group(2));
        } else if ((x = COMMENT_COLUMN.matcher(stmt)).find()) {
            List<String> parts = parts(x.group(1));
            String col = parts.get(parts.size() - 1);
            String tbl = parts.get(parts.size() - 2);
            String sch = parts.size() > 2 ? parts.get(0) : null;
            m.table(sch, tbl).column(col).comment = unescape(x.group(2));
        } else if ((x = ALTER_PK.matcher(stmt)).find()) {
            String[] sn = splitName(x.group(1));
            MTable t = m.table(sn[0], sn[1]);
            for (String c : list(x.group(2))) t.pk.add(Names.key(c));
        } else if ((x = ALTER_FK.matcher(stmt)).find()) {
            String[] sn = splitName(x.group(1));
            String[] rn = splitName(x.group(3));
            addFks(m, sn[1], list(x.group(2)), rn[1], list(x.group(4)));
        }
    }

    private boolean createTableWithParser(String stmt, Model m) {
        Statement st;
        try {
            st = CCJSqlParserUtil.parse(stmt, p -> p.withTimeOut(5_000));
        } catch (Throwable e) {
            return false;
        }
        if (!(st instanceof CreateTable ct) || ct.getTable() == null) return false;
        String schema = ct.getTable().getSchemaName() == null ? null : Names.unquote(ct.getTable().getSchemaName());
        MTable t = m.table(schema, Names.unquote(ct.getTable().getName()));
        if (ct.getColumnDefinitions() != null) {
            for (ColumnDefinition cd : ct.getColumnDefinitions()) {
                MColumn c = t.column(Names.unquote(cd.getColumnName()));
                ColDataType dt = cd.getColDataType();
                if (dt != null) {
                    c.type = dt.getDataType();
                    c.size = firstNumber(dt.getArgumentsStringList());
                }
                List<String> specs = cd.getColumnSpecs() == null ? List.of() : cd.getColumnSpecs();
                String spec = String.join(" ", specs);
                String specUpper = spec.toUpperCase(Locale.ROOT);
                if (specUpper.matches("(?s).*\\bNOT\\s+NULL\\b.*")) c.nullable = false;
                if (specUpper.matches("(?s).*\\bPRIMARY\\s+KEY\\b.*")) t.pk.add(Names.key(c.name));
                Matcher cm = COLUMN_COMMENT.matcher(spec);
                if (cm.find()) c.comment = unescape(cm.group(1));
            }
        }
        if (ct.getIndexes() != null) {
            for (Index idx : ct.getIndexes()) {
                if (idx instanceof ForeignKeyIndex fk && fk.getTable() != null) {
                    addFks(m, t.name, fk.getColumnsNames(), Names.unquote(fk.getTable().getName()),
                            fk.getReferencedColumnNames());
                } else if (idx.getType() != null && idx.getType().toUpperCase(Locale.ROOT).contains("PRIMARY")) {
                    for (String c : idx.getColumnsNames()) t.pk.add(Names.key(c));
                }
            }
        }
        if (ct.getTableOptionsStrings() != null) {
            Matcher tm = MYSQL_TABLE_COMMENT.matcher(String.join(" ", ct.getTableOptionsStrings()));
            if (tm.find()) t.comment = unescape(tm.group(1));
        }
        return true;
    }

    /** 파서가 못 읽는 벤더 옵션이 있을 때: 괄호 안을 최상위 콤마로 나눠 컬럼만 추출 */
    private boolean createTableFallback(String stmt, Model m) {
        Matcher x = CREATE_TABLE.matcher(stmt);
        if (!x.find()) return false;
        String[] sn = splitName(x.group(1));
        MTable t = m.table(sn[0], sn[1]);
        String body = balancedBody(stmt, x.start(2) - 1);
        if (body == null) body = x.group(2);
        String after = stmt.substring(Math.min(stmt.length(), stmt.indexOf(body) + body.length() + 1));
        for (String def : topLevelSplit(body)) {
            String d = def.strip();
            if (d.isEmpty()) continue;
            String first = d.split("\\s+")[0];
            if (CONSTRAINT_WORDS.contains(first.toUpperCase(Locale.ROOT))) {
                Matcher fk = INLINE_FK.matcher(d);
                if (fk.find()) {
                    addFks(m, t.name, list(fk.group(1)), splitName(fk.group(2))[1], list(fk.group(3)));
                } else {
                    Matcher pk = INLINE_PK.matcher(d);
                    if (pk.find()) for (String c : list(pk.group(1))) t.pk.add(Names.key(c));
                }
                continue;
            }
            MColumn c = t.column(Names.unquote(first));
            String rest = d.substring(first.length()).strip();
            Matcher type = Pattern.compile("^([\\w ]+?)(?:\\s*\\(([^)]*)\\))?(?=\\s|$)").matcher(rest);
            if (type.find()) {
                c.type = type.group(1).strip();
                if (type.group(2) != null) c.size = firstNumber(List.of(type.group(2).split(",")));
            }
            String ru = rest.toUpperCase(Locale.ROOT);
            if (ru.matches("(?s).*\\bNOT\\s+NULL\\b.*")) c.nullable = false;
            if (ru.matches("(?s).*\\bPRIMARY\\s+KEY\\b.*")) t.pk.add(Names.key(c.name));
            Matcher cm = COLUMN_COMMENT.matcher(rest);
            if (cm.find()) c.comment = unescape(cm.group(1));
        }
        Matcher tm = MYSQL_TABLE_COMMENT.matcher(after);
        if (tm.find()) t.comment = unescape(tm.group(1));
        return true;
    }

    private static void addFks(Model m, String table, List<String> cols, String refTable, List<String> refCols) {
        for (int i = 0; i < cols.size(); i++) {
            String rc = refCols != null && i < refCols.size() ? Names.unquote(refCols.get(i)) : null;
            m.fks.add(new ForeignKey(table, Names.unquote(cols.get(i)), refTable, rc));
        }
    }

    // ------------------------------------------------------------- splitting

    private static String balancedBody(String s, int openIdx) {
        if (openIdx < 0 || openIdx >= s.length() || s.charAt(openIdx) != '(') return null;
        int depth = 0;
        boolean inStr = false;
        for (int i = openIdx; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\'') inStr = !inStr;
            if (inStr) continue;
            if (c == '(') depth++;
            else if (c == ')' && --depth == 0) return s.substring(openIdx + 1, i);
        }
        return null;
    }

    private static List<String> topLevelSplit(String body) {
        List<String> out = new ArrayList<>();
        int depth = 0;
        boolean inStr = false;
        StringBuilder cur = new StringBuilder();
        for (char c : body.toCharArray()) {
            if (c == '\'') inStr = !inStr;
            if (!inStr) {
                if (c == '(') depth++;
                if (c == ')') depth--;
                if (c == ',' && depth == 0) {
                    out.add(cur.toString());
                    cur.setLength(0);
                    continue;
                }
            }
            cur.append(c);
        }
        out.add(cur.toString());
        return out;
    }

    // --------------------------------------------------------------- helpers

    private static String[] splitName(String q) {
        List<String> p = parts(q);
        return p.size() == 1 ? new String[]{null, p.get(0)} : new String[]{p.get(p.size() - 2), p.get(p.size() - 1)};
    }

    private static List<String> parts(String q) {
        List<String> r = new ArrayList<>();
        for (String s : q.split("\\s*\\.\\s*")) r.add(Names.unquote(s.strip()));
        return r;
    }

    private static List<String> list(String csv) {
        List<String> r = new ArrayList<>();
        for (String s : csv.split(",")) {
            String t = s.strip().split("\\s+")[0];   // "COL ASC" → COL
            if (!t.isEmpty()) r.add(Names.unquote(t));
        }
        return r;
    }

    private static Integer firstNumber(List<String> args) {
        if (args == null) return null;
        for (String a : args) {
            Matcher d = Pattern.compile("\\d+").matcher(a);
            if (d.find()) {
                try {
                    return Integer.parseInt(d.group());
                } catch (NumberFormatException ignore) {
                    return null;
                }
            }
        }
        return null;
    }

    private static String unescape(String s) {
        return s == null ? null : s.replace("''", "'").replace("\\'", "'");
    }

    private static String head(String s) {
        String one = s.replaceAll("\\s+", " ");
        return one.length() > 80 ? one.substring(0, 80) + "…" : one;
    }

    // ----------------------------------------------------------------- model

    static final class Model {
        final Map<String, MTable> tables = new LinkedHashMap<>();
        final List<ForeignKey> fks = new ArrayList<>();

        MTable table(String schema, String name) {
            MTable t = tables.computeIfAbsent(Names.key(name), k -> new MTable(name));
            if (schema != null && t.schema == null) t.schema = schema;
            return t;
        }

        SchemaSnapshot toSnapshot(String id, String product, List<String> warnings) {
            List<DbTable> out = new ArrayList<>();
            for (MTable t : tables.values()) {
                List<DbColumn> cols = new ArrayList<>();
                int ord = 1;
                for (MColumn c : t.columns.values()) {
                    cols.add(new DbColumn(c.name, c.type, c.size, c.nullable, t.pk.contains(Names.key(c.name)),
                            ord++, c.comment));
                }
                out.add(new DbTable(t.schema, t.name, t.kind, t.comment, cols));
            }
            return new SchemaSnapshot(id, product, out, fks, warnings);
        }
    }

    static final class MTable {
        final String name;
        String schema;
        String kind = "TABLE";
        String comment;
        final Map<String, MColumn> columns = new LinkedHashMap<>();
        final Set<String> pk = new LinkedHashSet<>();

        MTable(String name) {
            this.name = name;
        }

        MColumn column(String name) {
            return columns.computeIfAbsent(Names.key(name), k -> new MColumn(name));
        }
    }

    static final class MColumn {
        final String name;
        String type;
        Integer size;
        boolean nullable = true;
        String comment;

        MColumn(String name) {
            this.name = name;
        }
    }
}
