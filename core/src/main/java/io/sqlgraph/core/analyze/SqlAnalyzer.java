package io.sqlgraph.core.analyze;

import io.sqlgraph.core.Names;
import io.sqlgraph.core.model.Access;
import io.sqlgraph.core.model.AnalyzedSql;
import io.sqlgraph.core.model.ColumnRef;
import io.sqlgraph.core.model.JoinRef;
import io.sqlgraph.core.model.SqlKind;
import io.sqlgraph.core.model.TableRef;
import net.sf.jsqlparser.expression.LongValue;
import net.sf.jsqlparser.expression.StringValue;
import net.sf.jsqlparser.expression.operators.relational.EqualsTo;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.delete.Delete;
import net.sf.jsqlparser.statement.execute.Execute;
import net.sf.jsqlparser.statement.insert.Insert;
import net.sf.jsqlparser.statement.merge.Merge;
import net.sf.jsqlparser.statement.select.AllColumns;
import net.sf.jsqlparser.statement.select.AllTableColumns;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.select.SelectItem;
import net.sf.jsqlparser.statement.update.Update;
import net.sf.jsqlparser.statement.update.UpdateSet;
import net.sf.jsqlparser.statement.upsert.Upsert;
import net.sf.jsqlparser.util.TablesNamesFinder;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * JSqlParser 로 SQL 을 파싱해서 테이블/컬럼 사용 내역을 뽑는다.
 * 파싱에 실패하면 정규식으로 테이블명만 추출한다 (parsed=false).
 */
public final class SqlAnalyzer {

    /** 동적 SQL 자리표시자. ${...} 를 이 식별자로 바꿔서 파싱한다. */
    public static final String DYN = "__DYN__";

    private static final long PARSE_TIMEOUT_MS = 5_000;

    /** 컬럼처럼 보이지만 컬럼이 아닌 것들 */
    private static final Set<String> PSEUDO_COLUMNS = Set.of(
            "ROWNUM", "ROWID", "SYSDATE", "SYSTIMESTAMP", "LEVEL", "NEXTVAL", "CURRVAL",
            "CURRENT_TIMESTAMP", "CURRENT_DATE", "CURRENT_TIME", "LOCALTIMESTAMP",
            "NULL", "TRUE", "FALSE", "DUAL", "USER", "UID", "NOW");

    private static final Pattern MYBATIS_BIND = Pattern.compile("#\\{[^}]*}");
    private static final Pattern MYBATIS_RAW = Pattern.compile("\\$\\{[^}]*}");
    private static final Pattern IBATIS_BIND = Pattern.compile("#[A-Za-z_][\\w.\\[\\]]*(?::[\\w]+)*#");
    private static final Pattern IBATIS_RAW = Pattern.compile("\\$[A-Za-z_][\\w.\\[\\]]*\\$");

    private static final Pattern JDBC_CALL = Pattern.compile("(?is)^\\{\\s*(?:\\?\\s*=\\s*)?call\\s+(.*?)\\s*}$");

    private static final String IDENT = "(?:\"[^\"]+\"|`[^`]+`|\\[[^\\]]+]|[A-Za-z_\\p{L}][\\w$#\\p{L}]*)";
    private static final Pattern FALLBACK_TABLE = Pattern.compile(
            "(?i)\\b(FROM|JOIN|INTO|UPDATE|USING)\\s+(" + IDENT + "(?:\\s*\\.\\s*" + IDENT + ")?)");
    private static final Set<String> FALLBACK_STOP = Set.of(
            "SELECT", "WHERE", "SET", "VALUES", "DUAL", "TABLE", "LATERAL", "UNNEST", DYN);

    public AnalyzedSql analyze(String sql) {
        String cleaned = normalize(sql);
        if (SqlKind.guess(cleaned) == SqlKind.CALL) {
            // 프로시저 호출은 테이블을 직접 참조하지 않는다 (본문은 DB 쪽). 파싱 실패로 세지 않음
            return new AnalyzedSql(SqlKind.CALL, true, null, List.of(), List.of(), false, List.of());
        }
        try {
            Statement st = CCJSqlParserUtil.parse(cleaned, p -> p.withTimeOut(PARSE_TIMEOUT_MS));
            return fromStatement(st);
        } catch (Throwable e) {
            return fallback(cleaned, shortError(e));
        }
    }

    /** 바인드 변수/동적 요소를 파싱 가능한 형태로 바꾼다. */
    public static String normalize(String sql) {
        String s = sql == null ? "" : sql;
        s = MYBATIS_BIND.matcher(s).replaceAll("?");
        s = MYBATIS_RAW.matcher(s).replaceAll(DYN);
        s = IBATIS_BIND.matcher(s).replaceAll("?");
        s = IBATIS_RAW.matcher(s).replaceAll(DYN);
        s = s.strip();
        while (s.endsWith(";") || s.endsWith("/")) s = s.substring(0, s.length() - 1).strip();
        // JDBC 이스케이프 구문: { call P(?) } / { ? = call F(?) } → CALL P(?)
        Matcher call = JDBC_CALL.matcher(s);
        if (call.matches()) s = "CALL " + call.group(1).strip();
        return s;
    }

    // ------------------------------------------------------------------ parsed

    private AnalyzedSql fromStatement(Statement st) {
        Collector c = new Collector();
        Set<String> realNames = c.getTables(st);   // CTE 이름 등은 제외된 실제 테이블명

        // 쓰기 대상 테이블
        Set<Table> writeTables = Collections.newSetFromMap(new IdentityHashMap<>());
        List<Column> writeColumns = new ArrayList<>();
        SqlKind kind = SqlKind.OTHER;

        if (st instanceof Select) {
            kind = SqlKind.SELECT;
        } else if (st instanceof Insert ins) {
            kind = SqlKind.INSERT;
            add(writeTables, ins.getTable());
            if (ins.getColumns() != null) writeColumns.addAll(ins.getColumns());
        } else if (st instanceof Update upd) {
            kind = SqlKind.UPDATE;
            add(writeTables, upd.getTable());
            if (upd.getUpdateSets() != null) {
                for (UpdateSet us : upd.getUpdateSets()) {
                    if (us.getColumns() != null) writeColumns.addAll(us.getColumns());
                }
            }
        } else if (st instanceof Delete del) {
            kind = SqlKind.DELETE;
            add(writeTables, del.getTable());
            if (del.getTables() != null) del.getTables().forEach(t -> add(writeTables, t));
        } else if (st instanceof Merge m) {
            kind = SqlKind.MERGE;
            add(writeTables, m.getTable());
            if (m.getMergeUpdate() != null && m.getMergeUpdate().getUpdateSets() != null) {
                for (UpdateSet us : m.getMergeUpdate().getUpdateSets()) {
                    if (us.getColumns() != null) writeColumns.addAll(us.getColumns());
                }
            }
            if (m.getMergeInsert() != null && m.getMergeInsert().getColumns() != null) {
                writeColumns.addAll(m.getMergeInsert().getColumns());
            }
        } else if (st instanceof Upsert up) {
            kind = SqlKind.MERGE;
            add(writeTables, up.getTable());
            if (up.getColumns() != null) writeColumns.addAll(up.getColumns());
        } else if (st instanceof Execute) {
            kind = SqlKind.CALL;
        } else {
            kind = SqlKind.guess(st.toString());
        }

        // 테이블 + 별칭 맵
        Map<String, String> aliasToTable = new HashMap<>();
        Set<String> tableKeys = new LinkedHashSet<>();
        LinkedHashSet<TableRef> tables = new LinkedHashSet<>();
        for (Table t : c.tables) {
            if (!realNames.contains(t.getFullyQualifiedName())) continue;   // CTE 등
            String name = Names.unquote(t.getName());
            if (name == null || name.contains(DYN)) continue;
            String schema = t.getSchemaName() == null ? null : Names.unquote(t.getSchemaName());
            Access acc = writeTables.contains(t) ? Access.WRITE : Access.READ;
            tables.add(new TableRef(schema, name, acc));
            String key = Names.key(name);
            tableKeys.add(key);
            aliasToTable.put(key, name);
            if (t.getAlias() != null && t.getAlias().getName() != null) {
                aliasToTable.put(Names.key(t.getAlias().getName()), name);
            }
        }
        // 대상 테이블에 걸린 별칭 (UPDATE HR_EMP e SET e.X = ...)
        for (Table t : writeTables) {
            if (t.getAlias() != null && t.getAlias().getName() != null && t.getName() != null) {
                aliasToTable.put(Names.key(t.getAlias().getName()), Names.unquote(t.getName()));
            }
        }

        String single = tableKeys.size() == 1 ? aliasToTable.get(tableKeys.iterator().next()) : null;
        if (single == null && kind == SqlKind.INSERT) {
            // INSERT ... SELECT: 읽기 컬럼은 SELECT 쪽에서만 나오므로 읽기 테이블이 하나면 그 테이블
            List<String> readOnly = tables.stream()
                    .filter(t -> t.access() == Access.READ
                            && tables.stream().noneMatch(w -> w.access() == Access.WRITE && w.name().equalsIgnoreCase(t.name())))
                    .map(TableRef::name).distinct().toList();
            if (readOnly.size() == 1) single = readOnly.get(0);
        }

        LinkedHashSet<ColumnRef> columns = new LinkedHashSet<>();
        Set<Column> writeSet = Collections.newSetFromMap(new IdentityHashMap<>());
        writeSet.addAll(writeColumns);
        for (Column col : writeColumns) {
            ColumnRef r = toRef(col, Access.WRITE, aliasToTable, single, writeTables);
            if (r != null) columns.add(r);
        }
        for (Column col : c.columns) {
            if (writeSet.contains(col)) continue;
            ColumnRef r = toRef(col, Access.READ, aliasToTable, single, writeTables);
            if (r != null) columns.add(r);
        }
        // 다중 테이블 UPDATE (UPDATE A JOIN B SET B.X = ...) : 쓰기 컬럼이 속한 테이블도 쓰기로 표시
        for (ColumnRef cr : columns) {
            if (cr.access() != Access.WRITE || cr.table() == null) continue;
            String tk = Names.key(cr.table());
            boolean already = tables.stream().anyMatch(t -> t.access() == Access.WRITE && Names.key(t.name()).equals(tk));
            if (already) continue;
            tables.stream().filter(t -> Names.key(t.name()).equals(tk)).findFirst()
                    .ifPresent(t -> tables.add(new TableRef(t.schema(), t.name(), Access.WRITE)));
        }
        return new AnalyzedSql(kind, true, null, List.copyOf(tables), List.copyOf(columns), c.selectsAll,
                joins(c, aliasToTable, single));
    }

    /**
     * 컬럼 = 컬럼 조건을 관계로. 같은 별칭끼리(a.X = a.Y)와 같은 컬럼 자기 자신은 제외.
     * 셀프 조인(d1.UPPER_DEPT_CD = d2.DEPT_CD)은 별칭이 다르면 포함한다.
     */
    private static List<JoinRef> joins(Collector c, Map<String, String> aliasToTable, String single) {
        // 별칭(또는 테이블) 키별 "컬럼 = 상수" 조건
        Map<String, List<String>> literals = new HashMap<>();
        for (EqualsTo eq : c.equalities) {
            Column col = null;
            String val = null;
            if (eq.getLeftExpression() instanceof Column l) {
                col = l;
                val = literal(eq.getRightExpression());
            }
            if (val == null && eq.getRightExpression() instanceof Column r) {
                col = r;
                val = literal(eq.getLeftExpression());
            }
            if (col == null || val == null) continue;
            String q = qualifierKey(col, single);
            if (q == null) continue;
            literals.computeIfAbsent(q, k -> new ArrayList<>())
                    .add(Names.key(Names.unquote(col.getColumnName())) + "=" + val);
        }
        LinkedHashSet<JoinRef> out = new LinkedHashSet<>();
        for (EqualsTo eq : c.equalities) {
            if (!(eq.getLeftExpression() instanceof Column l) || !(eq.getRightExpression() instanceof Column r)) continue;
            String lq = qualifierKey(l, null);
            String rq = qualifierKey(r, null);
            String lc = Names.unquote(l.getColumnName());
            String rc = Names.unquote(r.getColumnName());
            if (lc == null || rc == null || lc.contains(DYN) || rc.contains(DYN)) continue;
            if (PSEUDO_COLUMNS.contains(Names.key(lc)) || PSEUDO_COLUMNS.contains(Names.key(rc))) continue;
            if (lq != null && lq.equals(rq)) continue;            // 같은 별칭 안의 비교
            String lt = lq == null ? null : aliasToTable.getOrDefault(lq, Names.unquote(l.getTable().getName()));
            String rt = rq == null ? null : aliasToTable.getOrDefault(rq, Names.unquote(r.getTable().getName()));
            if (lt != null && lt.equalsIgnoreCase(String.valueOf(rt)) && lc.equalsIgnoreCase(rc)) continue;
            out.add(new JoinRef(lt, lc, rt, rc,
                    lq == null ? List.of() : List.copyOf(literals.getOrDefault(lq, List.of())),
                    rq == null ? List.of() : List.copyOf(literals.getOrDefault(rq, List.of()))));
        }
        return List.copyOf(out);
    }

    /** 컬럼의 한정자(별칭/테이블명) 키. 없으면 단일 테이블 쿼리일 때만 그 테이블 */
    private static String qualifierKey(Column col, String single) {
        Table t = col.getTable();
        if (t != null && t.getName() != null) return Names.key(t.getName());
        return single == null ? null : Names.key(single);
    }

    /** 문자열/숫자 상수면 SQL 표기로, 아니면 null (바인드 변수 ? 는 상수가 아님) */
    private static String literal(Object e) {
        if (e instanceof StringValue sv) return "'" + sv.getValue() + "'";
        if (e instanceof LongValue lv) return lv.getStringValue();
        return null;
    }

    private static ColumnRef toRef(Column col, Access acc, Map<String, String> aliasToTable,
                                   String single, Set<Table> writeTables) {
        String name = Names.unquote(col.getColumnName());
        if (name == null || name.isBlank() || name.contains(DYN) || name.equals("?")) return null;
        String key = Names.key(name);
        if (PSEUDO_COLUMNS.contains(key)) return null;
        if (key.startsWith(":")) return null;   // 바인드 변수
        String table = null;
        Table qt = col.getTable();
        if (qt != null && qt.getName() != null) {
            String q = Names.key(qt.getName());
            table = aliasToTable.get(q);
            if (table == null) {
                // 시퀀스.NEXTVAL 같은 것은 위에서 걸러짐. 모르는 한정자는 테이블명으로 간주
                table = Names.unquote(qt.getName());
                if (table.contains(DYN)) table = null;
            }
        } else if (acc == Access.WRITE && writeTables.size() == 1) {
            table = Names.unquote(writeTables.iterator().next().getName());
        } else {
            table = single;
        }
        return new ColumnRef(table, name, acc);
    }

    private static void add(Set<Table> set, Table t) {
        if (t != null) set.add(t);
    }

    /** TablesNamesFinder 를 확장해서 Table 객체(별칭 포함)와 Column 을 함께 모은다. */
    private static final class Collector extends TablesNamesFinder {
        final List<Table> tables = new ArrayList<>();
        final List<Column> columns = new ArrayList<>();
        final List<EqualsTo> equalities = new ArrayList<>();
        boolean selectsAll;

        @Override
        public void visit(EqualsTo equalsTo) {
            equalities.add(equalsTo);
            super.visit(equalsTo);
        }

        @Override
        public void visit(Table table) {
            super.visit(table);
            tables.add(table);
        }

        @Override
        public void visit(Column column) {
            super.visit(column);
            columns.add(column);
        }

        /** SELECT 목록에 직접 * 가 있을 때만 표시 (COUNT(*) 는 제외) */
        @Override
        public void visit(SelectItem item) {
            Object e = item.getExpression();
            if (e instanceof AllColumns || e instanceof AllTableColumns) selectsAll = true;
            super.visit(item);
        }
    }

    // ---------------------------------------------------------------- fallback

    private AnalyzedSql fallback(String sql, String error) {
        SqlKind kind = SqlKind.guess(sql);
        String noComments = sql.replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("--[^\\n]*", " ");
        LinkedHashSet<TableRef> tables = new LinkedHashSet<>();
        boolean firstWrite = kind == SqlKind.INSERT || kind == SqlKind.UPDATE
                || kind == SqlKind.DELETE || kind == SqlKind.MERGE;
        Matcher m = FALLBACK_TABLE.matcher(noComments);
        boolean writeAssigned = false;
        while (m.find()) {
            String kw = m.group(1).toUpperCase();
            String full = m.group(2).replaceAll("\\s+", "");
            String name = Names.unquote(Names.lastPart(full));
            if (name == null || FALLBACK_STOP.contains(Names.key(name)) || name.contains(DYN)) continue;
            String schema = full.contains(".") ? Names.unquote(full.substring(0, full.lastIndexOf('.'))) : null;
            Access acc = Access.READ;
            if (firstWrite && !writeAssigned
                    && (kw.equals("INTO") || kw.equals("UPDATE") || (kind == SqlKind.DELETE && kw.equals("FROM")))) {
                acc = Access.WRITE;
                writeAssigned = true;
            }
            tables.add(new TableRef(schema, name, acc));
        }
        boolean star = Pattern.compile("(?i)SELECT\\s+(DISTINCT\\s+)?\\*").matcher(noComments).find();
        return new AnalyzedSql(kind, false, error, List.copyOf(tables), List.of(), star, List.of());
    }

    private static String shortError(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null && t.getCause() != t) t = t.getCause();
        String msg = t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
        msg = msg.replaceAll("\\s+", " ").strip();
        return msg.length() > 200 ? msg.substring(0, 200) + "…" : msg;
    }
}
