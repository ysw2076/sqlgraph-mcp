package io.sqlgraph.core.store;

import io.sqlgraph.core.Names;
import io.sqlgraph.core.model.AnalyzedSql;
import io.sqlgraph.core.model.ColumnRef;
import io.sqlgraph.core.model.DbColumn;
import io.sqlgraph.core.model.DbTable;
import io.sqlgraph.core.model.ExtractedSql;
import io.sqlgraph.core.model.ForeignKey;
import io.sqlgraph.core.model.SchemaSnapshot;
import io.sqlgraph.core.model.TableRef;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 인덱스 저장소 (SQLite 파일 하나).
 * 테이블명/컬럼명은 대문자 키(*_key)로 매칭한다.
 */
public final class GraphStore implements AutoCloseable {

    /** 인덱싱된 쿼리 한 건 (저장 단위) */
    public record IndexedStmt(ExtractedSql source, AnalyzedSql analysis) {}

    public record TableHit(long id, String db, String schema, String name, String kind, String comment,
                           int columnCount, int stmtCount) {}

    public record ColumnInfo(String name, String type, Integer size, boolean nullable, boolean pk,
                             String comment) {}

    public record StmtHit(long id, String owner, String kind, String file, int line, String access,
                          boolean dynamic, boolean parsed, boolean selectAll) {}

    public record StmtDetail(long id, String owner, String kind, String file, int line, boolean dynamic,
                             boolean parsed, String error, String sql, List<String> tables) {}

    public record FkInfo(String column, String refTable, String refColumn) {}

    private static final int SCHEMA_VERSION = 1;
    private final Connection conn;

    private GraphStore(Connection conn) {
        this.conn = conn;
    }

    public static GraphStore open(Path file) throws SQLException {
        try {
            if (file.getParent() != null) Files.createDirectories(file.getParent());
        } catch (Exception e) {
            throw new SQLException("인덱스 폴더를 만들 수 없습니다: " + file.getParent(), e);
        }
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException e) {
            throw new SQLException("sqlite-jdbc 가 클래스패스에 없습니다", e);
        }
        Connection c = DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath());
        try (Statement s = c.createStatement()) {
            s.execute("PRAGMA journal_mode=WAL");
            s.execute("PRAGMA foreign_keys=OFF");
        }
        GraphStore g = new GraphStore(c);
        g.createSchema();
        return g;
    }

    public static boolean exists(Path file) {
        return Files.isRegularFile(file);
    }

    private void createSchema() throws SQLException {
        try (Statement s = conn.createStatement()) {
            s.execute("CREATE TABLE IF NOT EXISTS meta(k TEXT PRIMARY KEY, v TEXT)");
            s.execute("CREATE TABLE IF NOT EXISTS db(id TEXT PRIMARY KEY, product TEXT)");
            s.execute("""
                CREATE TABLE IF NOT EXISTS tbl(
                  id INTEGER PRIMARY KEY, db_id TEXT, schema_name TEXT, name TEXT, name_key TEXT,
                  kind TEXT, comment TEXT)""");
            s.execute("CREATE INDEX IF NOT EXISTS ix_tbl_key ON tbl(name_key)");
            s.execute("""
                CREATE TABLE IF NOT EXISTS col(
                  id INTEGER PRIMARY KEY, tbl_id INTEGER, name TEXT, name_key TEXT, type TEXT,
                  size INTEGER, nullable INTEGER, pk INTEGER, ordinal INTEGER, comment TEXT)""");
            s.execute("CREATE INDEX IF NOT EXISTS ix_col_tbl ON col(tbl_id)");
            s.execute("CREATE INDEX IF NOT EXISTS ix_col_key ON col(name_key)");
            s.execute("""
                CREATE TABLE IF NOT EXISTS fk(
                  tbl_id INTEGER, col TEXT, ref_table TEXT, ref_col TEXT)""");
            s.execute("""
                CREATE TABLE IF NOT EXISTS stmt(
                  id INTEGER PRIMARY KEY, owner TEXT, kind TEXT, file TEXT, line INTEGER,
                  dynamic INTEGER, parsed INTEGER, error TEXT, select_all INTEGER, sql TEXT)""");
            s.execute("CREATE INDEX IF NOT EXISTS ix_stmt_owner ON stmt(owner)");
            s.execute("""
                CREATE TABLE IF NOT EXISTS stmt_tbl(
                  stmt_id INTEGER, schema_key TEXT, tbl_key TEXT, access TEXT)""");
            s.execute("CREATE INDEX IF NOT EXISTS ix_stmt_tbl ON stmt_tbl(tbl_key)");
            s.execute("""
                CREATE TABLE IF NOT EXISTS stmt_col(
                  stmt_id INTEGER, tbl_key TEXT, col_key TEXT, access TEXT)""");
            s.execute("CREATE INDEX IF NOT EXISTS ix_stmt_col ON stmt_col(tbl_key, col_key)");
            s.execute("CREATE INDEX IF NOT EXISTS ix_stmt_col2 ON stmt_col(col_key)");
        }
        setMeta("schema_version", String.valueOf(SCHEMA_VERSION));
    }

    // =================================================================== write

    /** 인덱스 전체를 새로 쓴다 (한 트랜잭션). */
    public synchronized void replaceAll(List<SchemaSnapshot> snapshots, List<IndexedStmt> stmts,
                                        Map<String, String> meta) throws SQLException {
        boolean auto = conn.getAutoCommit();
        conn.setAutoCommit(false);
        try (Statement s = conn.createStatement()) {
            for (String t : List.of("db", "tbl", "col", "fk", "stmt", "stmt_tbl", "stmt_col")) {
                s.execute("DELETE FROM " + t);
            }
            writeSchemas(snapshots);
            writeStmts(stmts);
            for (var e : meta.entrySet()) setMeta(e.getKey(), e.getValue());
            conn.commit();
        } catch (SQLException e) {
            conn.rollback();
            throw e;
        } finally {
            conn.setAutoCommit(auto);
        }
    }

    private void writeSchemas(List<SchemaSnapshot> snapshots) throws SQLException {
        try (PreparedStatement pdb = conn.prepareStatement("INSERT OR REPLACE INTO db(id, product) VALUES(?,?)");
             PreparedStatement pt = conn.prepareStatement(
                     "INSERT INTO tbl(id, db_id, schema_name, name, name_key, kind, comment) VALUES(?,?,?,?,?,?,?)");
             PreparedStatement pc = conn.prepareStatement(
                     "INSERT INTO col(tbl_id, name, name_key, type, size, nullable, pk, ordinal, comment) "
                             + "VALUES(?,?,?,?,?,?,?,?,?)");
             PreparedStatement pf = conn.prepareStatement(
                     "INSERT INTO fk(tbl_id, col, ref_table, ref_col) VALUES(?,?,?,?)")) {
            long nextId = 1;
            for (SchemaSnapshot snap : snapshots) {
                pdb.setString(1, snap.dbId());
                pdb.setString(2, snap.product());
                pdb.executeUpdate();
                Map<String, Long> ids = new LinkedHashMap<>();
                for (DbTable t : snap.tables()) {
                    long id = nextId++;
                    pt.setLong(1, id);
                    pt.setString(2, snap.dbId());
                    pt.setString(3, t.schema());
                    pt.setString(4, t.name());
                    pt.setString(5, Names.key(t.name()));
                    pt.setString(6, t.kind());
                    pt.setString(7, blankToNull(t.comment()));
                    pt.addBatch();
                    ids.put(Names.key(t.name()), id);
                    for (DbColumn c : t.columns()) {
                        pc.setLong(1, id);
                        pc.setString(2, c.name());
                        pc.setString(3, Names.key(c.name()));
                        pc.setString(4, c.type());
                        if (c.size() == null) pc.setNull(5, java.sql.Types.INTEGER); else pc.setInt(5, c.size());
                        pc.setInt(6, c.nullable() ? 1 : 0);
                        pc.setInt(7, c.primaryKey() ? 1 : 0);
                        pc.setInt(8, c.ordinal());
                        pc.setString(9, blankToNull(c.comment()));
                        pc.addBatch();
                    }
                }
                pt.executeBatch();
                pc.executeBatch();
                for (ForeignKey fk : snap.foreignKeys()) {
                    Long id = ids.get(Names.key(fk.table()));
                    if (id == null) continue;
                    pf.setLong(1, id);
                    pf.setString(2, fk.column());
                    pf.setString(3, fk.refTable());
                    pf.setString(4, fk.refColumn());
                    pf.addBatch();
                }
                pf.executeBatch();
            }
        }
    }

    private void writeStmts(List<IndexedStmt> stmts) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO stmt(id, owner, kind, file, line, dynamic, parsed, error, select_all, sql) "
                        + "VALUES(?,?,?,?,?,?,?,?,?,?)");
             PreparedStatement pt = conn.prepareStatement(
                     "INSERT INTO stmt_tbl(stmt_id, schema_key, tbl_key, access) VALUES(?,?,?,?)");
             PreparedStatement pc = conn.prepareStatement(
                     "INSERT INTO stmt_col(stmt_id, tbl_key, col_key, access) VALUES(?,?,?,?)")) {
            long id = 0;
            for (IndexedStmt is : stmts) {
                ExtractedSql src = is.source();
                AnalyzedSql an = is.analysis();
                id++;
                ps.setLong(1, id);
                ps.setString(2, src.ownerId());
                ps.setString(3, (src.kind() != null ? src.kind() : an.kind()).name());
                ps.setString(4, src.location().file());
                ps.setInt(5, src.location().line());
                ps.setInt(6, src.dynamic() ? 1 : 0);
                ps.setInt(7, an.parsed() ? 1 : 0);
                ps.setString(8, an.error());
                ps.setInt(9, an.selectsAll() ? 1 : 0);
                ps.setString(10, src.sql());
                ps.addBatch();
                for (TableRef t : an.tables()) {
                    pt.setLong(1, id);
                    pt.setString(2, Names.key(t.schema()));
                    pt.setString(3, Names.key(t.name()));
                    pt.setString(4, t.access().name());
                    pt.addBatch();
                }
                for (ColumnRef c : an.columns()) {
                    pc.setLong(1, id);
                    pc.setString(2, Names.key(c.table()));
                    pc.setString(3, Names.key(c.column()));
                    pc.setString(4, c.access().name());
                    pc.addBatch();
                }
            }
            ps.executeBatch();
            pt.executeBatch();
            pc.executeBatch();
        }
    }

    public void setMeta(String k, String v) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("INSERT OR REPLACE INTO meta(k, v) VALUES(?,?)")) {
            ps.setString(1, k);
            ps.setString(2, v);
            ps.executeUpdate();
        }
    }

    // ==================================================================== read

    public Map<String, String> meta() throws SQLException {
        Map<String, String> m = new LinkedHashMap<>();
        try (Statement s = conn.createStatement(); ResultSet rs = s.executeQuery("SELECT k, v FROM meta ORDER BY k")) {
            while (rs.next()) m.put(rs.getString(1), rs.getString(2));
        }
        return m;
    }

    public Map<String, Integer> counts() throws SQLException {
        Map<String, Integer> m = new LinkedHashMap<>();
        m.put("databases", count("SELECT COUNT(*) FROM db"));
        m.put("tables", count("SELECT COUNT(*) FROM tbl"));
        m.put("columns", count("SELECT COUNT(*) FROM col"));
        m.put("statements", count("SELECT COUNT(*) FROM stmt"));
        m.put("parseFailed", count("SELECT COUNT(*) FROM stmt WHERE parsed = 0"));
        m.put("dynamic", count("SELECT COUNT(*) FROM stmt WHERE dynamic = 1"));
        m.put("unknownTables", count("""
                SELECT COUNT(DISTINCT st.tbl_key) FROM stmt_tbl st
                WHERE NOT EXISTS (SELECT 1 FROM tbl t WHERE t.name_key = st.tbl_key)"""));
        return m;
    }

    public List<Map.Entry<String, String>> databases() throws SQLException {
        List<Map.Entry<String, String>> r = new ArrayList<>();
        try (Statement s = conn.createStatement(); ResultSet rs = s.executeQuery("SELECT id, product FROM db ORDER BY id")) {
            while (rs.next()) r.add(Map.entry(rs.getString(1), String.valueOf(rs.getString(2))));
        }
        return r;
    }

    /** 공백으로 나눈 키워드가 모두 (테이블명 또는 코멘트 또는 컬럼명/코멘트) 에 포함되는 테이블 */
    public List<TableHit> searchTables(String keyword, boolean includeColumns, int limit) throws SQLException {
        String[] tokens = keyword == null ? new String[0] : keyword.strip().split("\\s+");
        StringBuilder where = new StringBuilder("1=1");
        List<String> params = new ArrayList<>();
        for (String tok : tokens) {
            if (tok.isBlank()) continue;
            where.append(" AND (t.name_key LIKE ? OR t.comment LIKE ?");
            params.add("%" + tok.toUpperCase() + "%");
            params.add("%" + tok + "%");
            if (includeColumns) {
                where.append(" OR EXISTS (SELECT 1 FROM col c WHERE c.tbl_id = t.id AND (c.name_key LIKE ? OR c.comment LIKE ?))");
                params.add("%" + tok.toUpperCase() + "%");
                params.add("%" + tok + "%");
            }
            where.append(")");
        }
        String sql = """
                SELECT t.id, t.db_id, t.schema_name, t.name, t.kind, t.comment,
                       (SELECT COUNT(*) FROM col c WHERE c.tbl_id = t.id),
                       (SELECT COUNT(DISTINCT st.stmt_id) FROM stmt_tbl st WHERE st.tbl_key = t.name_key)
                FROM tbl t WHERE %s
                ORDER BY CASE WHEN t.name_key = ? THEN 0 ELSE 1 END, t.name_key LIMIT ?""".formatted(where);
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            int i = 1;
            for (String p : params) ps.setString(i++, p);
            ps.setString(i++, keyword == null ? "" : keyword.strip().toUpperCase());
            ps.setInt(i, limit);
            return tableHits(ps);
        }
    }

    /** 정확한 이름(대소문자 무시)으로 테이블 찾기. "SCHEMA.TABLE" 형식도 허용 */
    public List<TableHit> findTables(String name, String db) throws SQLException {
        String schema = name.contains(".") ? Names.key(name.substring(0, name.lastIndexOf('.'))) : null;
        String key = Names.key(Names.lastPart(name));
        String sql = """
                SELECT t.id, t.db_id, t.schema_name, t.name, t.kind, t.comment,
                       (SELECT COUNT(*) FROM col c WHERE c.tbl_id = t.id),
                       (SELECT COUNT(DISTINCT st.stmt_id) FROM stmt_tbl st WHERE st.tbl_key = t.name_key)
                FROM tbl t WHERE t.name_key = ?
                  AND (? IS NULL OR UPPER(t.schema_name) = ?)
                  AND (? IS NULL OR t.db_id = ?)
                ORDER BY t.db_id""";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, key);
            ps.setString(2, schema);
            ps.setString(3, schema);
            ps.setString(4, db);
            ps.setString(5, db);
            return tableHits(ps);
        }
    }

    private List<TableHit> tableHits(PreparedStatement ps) throws SQLException {
        List<TableHit> r = new ArrayList<>();
        try (ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                r.add(new TableHit(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getString(5), rs.getString(6), rs.getInt(7), rs.getInt(8)));
            }
        }
        return r;
    }

    public List<ColumnInfo> columns(long tableId) throws SQLException {
        List<ColumnInfo> r = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT name, type, size, nullable, pk, comment FROM col WHERE tbl_id = ? ORDER BY ordinal, id")) {
            ps.setLong(1, tableId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    int sizeVal = rs.getInt(3);
                    Integer size = rs.wasNull() ? null : sizeVal;
                    r.add(new ColumnInfo(rs.getString(1), rs.getString(2), size,
                            rs.getInt(4) == 1, rs.getInt(5) == 1, rs.getString(6)));
                }
            }
        }
        return r;
    }

    public List<FkInfo> foreignKeys(long tableId) throws SQLException {
        List<FkInfo> r = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement("SELECT col, ref_table, ref_col FROM fk WHERE tbl_id = ?")) {
            ps.setLong(1, tableId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) r.add(new FkInfo(rs.getString(1), rs.getString(2), rs.getString(3)));
            }
        }
        return r;
    }

    /** 이 테이블을 참조하는 다른 테이블의 FK */
    public List<String> referencedBy(String tableName) throws SQLException {
        List<String> r = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement("""
                SELECT t.name, f.col, f.ref_col FROM fk f JOIN tbl t ON t.id = f.tbl_id
                WHERE UPPER(f.ref_table) = ? ORDER BY t.name""")) {
            ps.setString(1, Names.key(tableName));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) r.add(rs.getString(1) + "." + rs.getString(2) + " → " + rs.getString(3));
            }
        }
        return r;
    }

    /** 테이블을 사용하는 쿼리 (접근 방식 포함) */
    public List<StmtHit> stmtsForTable(String tableName, int limit) throws SQLException {
        String sql = """
                SELECT s.id, s.owner, s.kind, s.file, s.line,
                       GROUP_CONCAT(DISTINCT st.access), s.dynamic, s.parsed, s.select_all
                FROM stmt_tbl st JOIN stmt s ON s.id = st.stmt_id
                WHERE st.tbl_key = ?
                GROUP BY s.id ORDER BY s.file, s.line LIMIT ?""";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, Names.key(Names.lastPart(tableName)));
            ps.setInt(2, limit);
            return stmtHits(ps);
        }
    }

    public int countStmtsForTable(String tableName) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT COUNT(DISTINCT stmt_id) FROM stmt_tbl WHERE tbl_key = ?")) {
            ps.setString(1, Names.key(Names.lastPart(tableName)));
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    /** 테이블을 사용하는 쿼리 수: [쓰기, 읽기] */
    public int[] accessCounts(String tableName) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("""
                SELECT SUM(CASE WHEN w > 0 THEN 1 ELSE 0 END), SUM(CASE WHEN w = 0 THEN 1 ELSE 0 END)
                FROM (SELECT stmt_id, SUM(CASE WHEN access = 'WRITE' THEN 1 ELSE 0 END) w
                      FROM stmt_tbl WHERE tbl_key = ? GROUP BY stmt_id)""")) {
            ps.setString(1, Names.key(Names.lastPart(tableName)));
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? new int[]{rs.getInt(1), rs.getInt(2)} : new int[]{0, 0};
            }
        }
    }

    /** 컬럼을 확실히 사용하는 쿼리 */
    public List<StmtHit> stmtsForColumn(String tableName, String column, int limit) throws SQLException {
        String sql = """
                SELECT s.id, s.owner, s.kind, s.file, s.line,
                       GROUP_CONCAT(DISTINCT sc.access), s.dynamic, s.parsed, s.select_all
                FROM stmt_col sc JOIN stmt s ON s.id = sc.stmt_id
                WHERE sc.tbl_key = ? AND sc.col_key = ?
                GROUP BY s.id ORDER BY s.file, s.line LIMIT ?""";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, Names.key(Names.lastPart(tableName)));
            ps.setString(2, Names.key(column));
            ps.setInt(3, limit);
            return stmtHits(ps);
        }
    }

    /**
     * 컬럼을 사용할 "가능성"이 있는 쿼리:
     * 테이블은 쓰지만 컬럼 사용이 확인되지 않은 것 중 SELECT *, 테이블 미지정 동명 컬럼, 파싱 실패, 동적 SQL.
     */
    public List<StmtHit> possibleStmtsForColumn(String tableName, String column, int limit) throws SQLException {
        String sql = """
                SELECT s.id, s.owner, s.kind, s.file, s.line,
                       GROUP_CONCAT(DISTINCT st.access), s.dynamic, s.parsed, s.select_all
                FROM stmt_tbl st JOIN stmt s ON s.id = st.stmt_id
                WHERE st.tbl_key = ?
                  AND NOT EXISTS (SELECT 1 FROM stmt_col x
                                  WHERE x.stmt_id = s.id AND x.tbl_key = ? AND x.col_key = ?)
                  AND (s.select_all = 1 OR s.parsed = 0 OR s.dynamic = 1
                       OR EXISTS (SELECT 1 FROM stmt_col y
                                  WHERE y.stmt_id = s.id AND y.tbl_key IS NULL AND y.col_key = ?))
                GROUP BY s.id ORDER BY s.file, s.line LIMIT ?""";
        String t = Names.key(Names.lastPart(tableName));
        String c = Names.key(column);
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, t);
            ps.setString(2, t);
            ps.setString(3, c);
            ps.setString(4, c);
            ps.setInt(5, limit);
            return stmtHits(ps);
        }
    }

    private List<StmtHit> stmtHits(PreparedStatement ps) throws SQLException {
        List<StmtHit> r = new ArrayList<>();
        try (ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                String acc = rs.getString(6);
                String access = acc == null ? "READ" : acc.contains("WRITE") ? "WRITE" : "READ";
                r.add(new StmtHit(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getInt(5), access, rs.getInt(7) == 1, rs.getInt(8) == 1, rs.getInt(9) == 1));
            }
        }
        return r;
    }

    /** ownerId 또는 SQL 본문에 키워드가 포함된 쿼리 */
    public List<StmtDetail> findQueries(String keyword, int limit) throws SQLException {
        String kw = keyword == null ? "" : keyword.strip();
        String sql = """
                SELECT id, owner, kind, file, line, dynamic, parsed, error, sql FROM stmt
                WHERE owner = ? OR owner LIKE ? OR UPPER(sql) LIKE ?
                ORDER BY CASE WHEN owner = ? THEN 0
                              WHEN owner LIKE ? THEN 1 ELSE 2 END, file, line
                LIMIT ?""";
        List<StmtDetail> r = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, kw);
            ps.setString(2, "%" + kw + "%");
            ps.setString(3, "%" + kw.toUpperCase() + "%");
            ps.setString(4, kw);
            ps.setString(5, "%" + kw);
            ps.setInt(6, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    long id = rs.getLong(1);
                    r.add(new StmtDetail(id, rs.getString(2), rs.getString(3), rs.getString(4), rs.getInt(5),
                            rs.getInt(6) == 1, rs.getInt(7) == 1, rs.getString(8), rs.getString(9),
                            stmtTables(id)));
                }
            }
        }
        return r;
    }

    private List<String> stmtTables(long stmtId) throws SQLException {
        List<String> r = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT tbl_key, GROUP_CONCAT(DISTINCT access) FROM stmt_tbl WHERE stmt_id = ? GROUP BY tbl_key")) {
            ps.setLong(1, stmtId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String acc = rs.getString(2);
                    r.add(rs.getString(1) + (acc != null && acc.contains("WRITE") ? "(W)" : "(R)"));
                }
            }
        }
        return r;
    }

    /** 파싱 실패 쿼리 샘플 */
    public List<StmtDetail> failedSamples(int limit) throws SQLException {
        List<StmtDetail> r = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT id, owner, kind, file, line, dynamic, parsed, error, sql FROM stmt WHERE parsed = 0 LIMIT ?")) {
            ps.setInt(1, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    r.add(new StmtDetail(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4),
                            rs.getInt(5), rs.getInt(6) == 1, false, rs.getString(8), rs.getString(9), List.of()));
                }
            }
        }
        return r;
    }

    /** 쿼리에서 쓰이지만 DB 메타데이터에 없는 테이블 (오타, 다른 DB, 동의어 등) */
    public List<String> unknownTables(int limit) throws SQLException {
        List<String> r = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement("""
                SELECT st.tbl_key, COUNT(DISTINCT st.stmt_id) n FROM stmt_tbl st
                WHERE NOT EXISTS (SELECT 1 FROM tbl t WHERE t.name_key = st.tbl_key)
                GROUP BY st.tbl_key ORDER BY n DESC LIMIT ?""")) {
            ps.setInt(1, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) r.add(rs.getString(1) + " (" + rs.getInt(2) + ")");
            }
        }
        return r;
    }

    private int count(String sql) throws SQLException {
        try (Statement s = conn.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }

    @Override
    public void close() throws SQLException {
        conn.close();
    }
}
