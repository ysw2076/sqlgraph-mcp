package io.sqlgraph.connector;

import io.sqlgraph.core.model.DbColumn;
import io.sqlgraph.core.model.DbTable;
import io.sqlgraph.core.model.ForeignKey;
import io.sqlgraph.core.model.SchemaSnapshot;
import io.sqlgraph.core.spi.SchemaSource;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.ServiceLoader;
import java.util.Set;

/**
 * JDBC DatabaseMetaData 로 테이블/컬럼/코멘트/PK/FK 를 수집한다. 데이터는 읽지 않는다.
 * driverJar 를 주면 그 jar 에서 드라이버를 로드하고, 없으면 내장 드라이버를 쓴다.
 */
public final class JdbcSchemaSource implements SchemaSource {

    public record Settings(String id, String url, String user, String password, Path driverJar,
                           String driverClass, String schema, Map<String, String> properties) {}

    /** URL 접두어 → 드라이버 클래스 (fat jar 에서 ServiceLoader 등록이 빠져도 동작하도록 명시 로드) */
    private static final Map<String, List<String>> KNOWN_DRIVERS = new LinkedHashMap<>();

    static {
        KNOWN_DRIVERS.put("jdbc:mariadb:", List.of("org.mariadb.jdbc.Driver"));
        KNOWN_DRIVERS.put("jdbc:mysql:", List.of("com.mysql.cj.jdbc.Driver", "com.mysql.jdbc.Driver"));
        KNOWN_DRIVERS.put("jdbc:oracle:", List.of("oracle.jdbc.OracleDriver"));
        KNOWN_DRIVERS.put("jdbc:postgresql:", List.of("org.postgresql.Driver"));
        KNOWN_DRIVERS.put("jdbc:sqlserver:", List.of("com.microsoft.sqlserver.jdbc.SQLServerDriver"));
        KNOWN_DRIVERS.put("jdbc:tibero:", List.of("com.tmax.tibero.jdbc.TbDriver"));
        KNOWN_DRIVERS.put("jdbc:altibase:", List.of("Altibase.jdbc.driver.AltibaseDriver"));
        KNOWN_DRIVERS.put("jdbc:cubrid:", List.of("cubrid.jdbc.driver.CUBRIDDriver"));
        KNOWN_DRIVERS.put("jdbc:db2:", List.of("com.ibm.db2.jcc.DB2Driver"));
        KNOWN_DRIVERS.put("jdbc:sqlite:", List.of("org.sqlite.JDBC"));
        KNOWN_DRIVERS.put("jdbc:h2:", List.of("org.h2.Driver"));
    }

    private final Settings s;

    public JdbcSchemaSource(Settings settings) {
        this.s = settings;
    }

    @Override
    public String id() {
        return s.id();
    }

    @Override
    public SchemaSnapshot load() throws Exception {
        List<String> warnings = new ArrayList<>();
        String url = s.url();
        Properties props = new Properties();
        if (s.user() != null) props.setProperty("user", s.user());
        if (s.password() != null) props.setProperty("password", s.password());
        String lower = url.toLowerCase(Locale.ROOT);
        if (lower.startsWith("jdbc:oracle:")) props.putIfAbsent("remarksReporting", "true");   // 코멘트 조회
        if (lower.startsWith("jdbc:mysql:") || lower.startsWith("jdbc:mariadb:")) {
            props.putIfAbsent("useInformationSchema", "true");
        }
        if (s.properties() != null) props.putAll(s.properties());

        try (Connection conn = connect(url, props, warnings)) {
            conn.setReadOnly(true);
            DatabaseMetaData md = conn.getMetaData();
            String product = md.getDatabaseProductName() + " " + md.getDatabaseProductVersion();
            boolean catalogBased = lower.startsWith("jdbc:mysql:") || lower.startsWith("jdbc:mariadb:");

            String catalog = null;
            String schemaPattern = null;
            if (catalogBased) {
                catalog = s.schema() != null ? s.schema() : conn.getCatalog();
            } else {
                schemaPattern = s.schema() != null ? s.schema() : safeSchema(conn);
                if (schemaPattern != null && lower.startsWith("jdbc:oracle:")) {
                    schemaPattern = schemaPattern.toUpperCase(Locale.ROOT);
                }
            }

            // 테이블
            Map<String, String[]> tableInfo = new LinkedHashMap<>();   // name → {schema, kind, comment}
            try (ResultSet rs = md.getTables(catalog, schemaPattern, "%", new String[]{"TABLE", "VIEW"})) {
                while (rs.next()) {
                    String name = rs.getString("TABLE_NAME");
                    if (name == null || name.startsWith("BIN$")) continue;   // Oracle 휴지통
                    String sch = catalogBased ? rs.getString("TABLE_CAT") : rs.getString("TABLE_SCHEM");
                    tableInfo.put(name, new String[]{sch, rs.getString("TABLE_TYPE"), rs.getString("REMARKS")});
                }
            }

            // 컬럼 (한 번에 조회 후 테이블별로 묶기)
            Map<String, List<DbColumn>> cols = new LinkedHashMap<>();
            Map<String, List<String[]>> rawCols = new LinkedHashMap<>();
            try (ResultSet rs = md.getColumns(catalog, schemaPattern, "%", "%")) {
                while (rs.next()) {
                    String t = rs.getString("TABLE_NAME");
                    if (!tableInfo.containsKey(t)) continue;
                    rawCols.computeIfAbsent(t, k -> new ArrayList<>()).add(new String[]{
                            rs.getString("COLUMN_NAME"), rs.getString("TYPE_NAME"),
                            String.valueOf(rs.getInt("COLUMN_SIZE")), String.valueOf(rs.getInt("NULLABLE")),
                            String.valueOf(rs.getInt("ORDINAL_POSITION")), rs.getString("REMARKS")});
                }
            }

            // PK / FK (테이블별 조회)
            List<ForeignKey> fks = new ArrayList<>();
            Map<String, Set<String>> pks = new LinkedHashMap<>();
            int failedKeys = 0;
            for (var e : tableInfo.entrySet()) {
                String t = e.getKey();
                if (!"TABLE".equalsIgnoreCase(e.getValue()[1])) continue;
                try (ResultSet rs = md.getPrimaryKeys(catalog, schemaPattern, t)) {
                    Set<String> pk = new HashSet<>();
                    while (rs.next()) pk.add(rs.getString("COLUMN_NAME"));
                    pks.put(t, pk);
                } catch (SQLException ex) {
                    failedKeys++;
                }
                try (ResultSet rs = md.getImportedKeys(catalog, schemaPattern, t)) {
                    while (rs.next()) {
                        fks.add(new ForeignKey(t, rs.getString("FKCOLUMN_NAME"),
                                rs.getString("PKTABLE_NAME"), rs.getString("PKCOLUMN_NAME")));
                    }
                } catch (SQLException ex) {
                    failedKeys++;
                }
            }
            if (failedKeys > 0) warnings.add("PK/FK 조회 실패 " + failedKeys + "건 (권한 부족일 수 있음)");

            for (var e : rawCols.entrySet()) {
                Set<String> pk = pks.getOrDefault(e.getKey(), Set.of());
                List<DbColumn> list = new ArrayList<>();
                for (String[] c : e.getValue()) {
                    Integer size = sizeMatters(c[1]) ? parseInt(c[2]) : null;
                    list.add(new DbColumn(c[0], c[1], size, !"0".equals(c[3]), pk.contains(c[0]),
                            parseIntOr(c[4], list.size() + 1), c[5]));
                }
                cols.put(e.getKey(), list);
            }

            List<DbTable> tables = new ArrayList<>();
            for (var e : tableInfo.entrySet()) {
                String[] info = e.getValue();
                String kind = info[1] != null && info[1].toUpperCase(Locale.ROOT).contains("VIEW") ? "VIEW" : "TABLE";
                String comment = info[2];
                if (comment != null && comment.equalsIgnoreCase("VIEW")) comment = null;   // MariaDB 는 뷰 코멘트에 'VIEW' 를 넣는다
                tables.add(new DbTable(info[0], e.getKey(), kind, comment, cols.getOrDefault(e.getKey(), List.of())));
            }
            if (tables.isEmpty()) {
                warnings.add("테이블이 0개입니다. schema 설정(catalog=" + catalog + ", schema=" + schemaPattern
                        + ")과 계정 권한을 확인하세요");
            }
            return new SchemaSnapshot(s.id(), product, tables, fks, warnings);
        }
    }

    private Connection connect(String url, Properties props, List<String> warnings) throws Exception {
        if (s.driverJar() != null) {
            if (!Files.isRegularFile(s.driverJar())) {
                throw new IllegalArgumentException("driverJar 파일이 없습니다: " + s.driverJar());
            }
            URLClassLoader loader = new URLClassLoader(new URL[]{s.driverJar().toUri().toURL()},
                    JdbcSchemaSource.class.getClassLoader());
            Driver driver = null;
            if (s.driverClass() != null) {
                driver = (Driver) Class.forName(s.driverClass(), true, loader).getDeclaredConstructor().newInstance();
            } else {
                for (Driver d : ServiceLoader.load(Driver.class, loader)) {
                    if (d.getClass().getClassLoader() == loader && d.acceptsURL(url)) {
                        driver = d;
                        break;
                    }
                }
                if (driver == null) {
                    for (String cls : candidates(url)) {
                        try {
                            driver = (Driver) Class.forName(cls, true, loader).getDeclaredConstructor().newInstance();
                            break;
                        } catch (ClassNotFoundException ignore) {
                            // 다음 후보
                        }
                    }
                }
            }
            if (driver == null) {
                throw new IllegalArgumentException("driverJar 에서 URL 을 처리할 드라이버를 찾지 못했습니다. driverClass 를 지정하세요");
            }
            Connection c = driver.connect(url, props);
            if (c == null) throw new SQLException("드라이버가 URL 을 거부했습니다: " + url);
            return c;
        }

        boolean loaded = false;
        if (s.driverClass() != null) {
            Class.forName(s.driverClass());
            loaded = true;
        } else {
            for (String cls : candidates(url)) {
                try {
                    Class.forName(cls);
                    loaded = true;
                    break;
                } catch (ClassNotFoundException ignore) {
                    // 다음 후보
                }
            }
        }
        String lower = url.toLowerCase(Locale.ROOT);
        if (!loaded && lower.startsWith("jdbc:mysql:") && isPresent("org.mariadb.jdbc.Driver")) {
            // MySQL 드라이버가 없으면 내장 MariaDB 드라이버로 접속 (MySQL 호환)
            url = "jdbc:mariadb:" + url.substring("jdbc:mysql:".length());
            warnings.add("MySQL 드라이버가 없어 MariaDB 드라이버로 접속했습니다");
        }
        return DriverManager.getConnection(url, props);
    }

    private static List<String> candidates(String url) {
        String lower = url.toLowerCase(Locale.ROOT);
        for (var e : KNOWN_DRIVERS.entrySet()) {
            if (lower.startsWith(e.getKey())) return e.getValue();
        }
        return List.of();
    }

    private static boolean isPresent(String cls) {
        try {
            Class.forName(cls);
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    private static String safeSchema(Connection c) {
        try {
            return c.getSchema();
        } catch (Throwable e) {
            return null;
        }
    }

    /** 길이/정밀도가 의미 있는 타입만 크기를 남긴다 (DATE(10) 같은 표시 크기는 버림) */
    private static boolean sizeMatters(String type) {
        if (type == null) return false;
        String t = type.toUpperCase(Locale.ROOT);
        return t.contains("CHAR") || t.contains("BINARY") || t.contains("RAW") || t.contains("NUMBER")
                || t.contains("NUMERIC") || t.contains("DECIMAL") || t.equals("BIT");
    }

    private static Integer parseInt(String s) {
        try {
            int v = Integer.parseInt(s);
            return v > 0 ? v : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static int parseIntOr(String s, int def) {
        Integer v = parseInt(s);
        return v == null ? def : v;
    }
}
