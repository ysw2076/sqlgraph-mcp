package io.sqlgraph.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.sqlgraph.core.index.Indexer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 실제 DB 서버 통합 테스트 (Oracle / SQL Server / PostgreSQL / MySQL).
 *
 * <p>환경변수 SQLGRAPH_IT_&lt;DB&gt;_URL, _USER, _PASSWORD 가 있는 DB 만 실행한다 (로컬 빌드에서는 건너뜀).
 * GitHub Actions 의 .github/workflows/integration.yml 이 DB 컨테이너를 띄우고 실행한다.
 *
 * <p>각 DB 에서: 테이블·뷰·한글 코멘트·PK·FK 를 만든 뒤 → 그 DB 방언으로 쓴 Mapper 와 함께 인덱싱 →
 * 스키마 수집, 방언 SQL 파싱, 영향도 분석 결과를 확인한다.
 */
class RealDbIT {

    static Path home;
    static final ObjectMapper om = new ObjectMapper();

    record Case(String name, String url, String user, String password, List<String> drops, List<String> ddl,
                String dialectMapper, List<String> writeEmpNm) {
        @Override
        public String toString() {
            return name;
        }
    }

    @BeforeAll
    static void setUp() throws Exception {
        home = Files.createTempDirectory("sqlgraph-it-home");
    }

    @AfterAll
    static void tearDown() throws Exception {
        try (var s = Files.walk(home)) {
            s.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        }
    }

    private static String env(String db, String key) {
        return System.getenv("SQLGRAPH_IT_" + db + "_" + key);
    }

    static Stream<Arguments> databases() {
        List<Arguments> out = new ArrayList<>();
        for (String db : List.of("ORACLE", "SQLSERVER", "POSTGRES", "MYSQL")) {
            String url = env(db, "URL");
            if (url == null || url.isBlank()) continue;
            out.add(Arguments.of(caseFor(db, url, env(db, "USER"), env(db, "PASSWORD"))));
        }
        if (out.isEmpty()) out.add(Arguments.of((Object) null));   // 환경변수가 없으면 건너뛰는 한 건
        return out.stream();
    }

    private static final String COMMON_MAPPER = """
              <select id="selectEmp">
                SELECT E.EMP_NO, E.EMP_NM, D.DEPT_NM
                  FROM HR_EMP E JOIN HR_DEPT D ON D.DEPT_CD = E.DEPT_CD
                 WHERE E.EMP_NO = #{empNo}
              </select>
              <update id="updateSal">
                UPDATE HR_EMP SET SAL_AMT = #{sal} WHERE EMP_NO = #{empNo}
              </update>
            """;

    static Case caseFor(String db, String url, String user, String pw) {
        return switch (db) {
            case "ORACLE" -> new Case("oracle", url, user, pw,
                    List.of("DROP VIEW V_EMP_DEPT", "DROP TABLE HR_EMP", "DROP TABLE HR_DEPT"),
                    List.of("CREATE TABLE HR_DEPT (DEPT_CD VARCHAR2(10) PRIMARY KEY, DEPT_NM VARCHAR2(100), UPPER_DEPT_CD VARCHAR2(10))",
                            "COMMENT ON TABLE HR_DEPT IS '부서'",
                            "COMMENT ON COLUMN HR_DEPT.DEPT_NM IS '부서명'",
                            "CREATE TABLE HR_EMP (EMP_NO VARCHAR2(10) PRIMARY KEY, EMP_NM VARCHAR2(50), "
                                    + "DEPT_CD VARCHAR2(10) REFERENCES HR_DEPT(DEPT_CD), SAL_AMT NUMBER(12,2), HIRE_DT DATE)",
                            "COMMENT ON TABLE HR_EMP IS '사원'",
                            "COMMENT ON COLUMN HR_EMP.EMP_NM IS '성명'",
                            "CREATE VIEW V_EMP_DEPT AS SELECT E.EMP_NO, D.DEPT_NM FROM HR_EMP E JOIN HR_DEPT D ON D.DEPT_CD = E.DEPT_CD"),
                    """
                      <select id="oracleOuterJoin">
                        SELECT E.EMP_NO, NVL(D.DEPT_NM, '-') AS DEPT_NM, DECODE(E.DEPT_CD, NULL, 'N', 'Y') AS HAS_DEPT,
                               TO_CHAR(E.HIRE_DT, 'YYYYMMDD') AS HIRE_YMD
                          FROM HR_EMP E, HR_DEPT D
                         WHERE E.DEPT_CD = D.DEPT_CD(+)
                           AND ROWNUM &lt;= 10
                      </select>
                      <select id="oracleTree">
                        SELECT DEPT_CD, DEPT_NM, LEVEL AS LV
                          FROM HR_DEPT
                         START WITH UPPER_DEPT_CD IS NULL
                         CONNECT BY PRIOR DEPT_CD = UPPER_DEPT_CD
                      </select>
                      <update id="oracleMerge">
                        MERGE INTO HR_EMP T
                        USING (SELECT #{empNo} AS EMP_NO, #{nm} AS EMP_NM FROM DUAL) S
                           ON (T.EMP_NO = S.EMP_NO)
                         WHEN MATCHED THEN UPDATE SET T.EMP_NM = S.EMP_NM
                         WHEN NOT MATCHED THEN INSERT (EMP_NO, EMP_NM) VALUES (S.EMP_NO, S.EMP_NM)
                      </update>
                    """, List.of("oracleMerge"));
            case "SQLSERVER" -> new Case("sqlserver", url, user, pw,
                    List.of("DROP VIEW IF EXISTS V_EMP_DEPT", "DROP TABLE IF EXISTS HR_EMP", "DROP TABLE IF EXISTS HR_DEPT"),
                    List.of("CREATE TABLE HR_DEPT (DEPT_CD VARCHAR(10) NOT NULL PRIMARY KEY, DEPT_NM NVARCHAR(100), UPPER_DEPT_CD VARCHAR(10))",
                            "EXEC sp_addextendedproperty 'MS_Description', N'부서', 'SCHEMA', 'dbo', 'TABLE', 'HR_DEPT'",
                            "EXEC sp_addextendedproperty 'MS_Description', N'부서명', 'SCHEMA', 'dbo', 'TABLE', 'HR_DEPT', 'COLUMN', 'DEPT_NM'",
                            "CREATE TABLE HR_EMP (EMP_NO VARCHAR(10) NOT NULL PRIMARY KEY, EMP_NM NVARCHAR(50), "
                                    + "DEPT_CD VARCHAR(10) REFERENCES HR_DEPT(DEPT_CD), SAL_AMT DECIMAL(12,2), HIRE_DT DATE)",
                            "EXEC sp_addextendedproperty 'MS_Description', N'사원', 'SCHEMA', 'dbo', 'TABLE', 'HR_EMP'",
                            "EXEC sp_addextendedproperty 'MS_Description', N'성명', 'SCHEMA', 'dbo', 'TABLE', 'HR_EMP', 'COLUMN', 'EMP_NM'",
                            "CREATE VIEW V_EMP_DEPT AS SELECT E.EMP_NO, D.DEPT_NM FROM HR_EMP E JOIN HR_DEPT D ON D.DEPT_CD = E.DEPT_CD"),
                    """
                      <select id="mssqlTop">
                        SELECT TOP 10 E.EMP_NO, ISNULL(D.DEPT_NM, '-') AS DEPT_NM, CONVERT(VARCHAR(8), E.HIRE_DT, 112) AS HIRE_YMD
                          FROM HR_EMP E WITH (NOLOCK) LEFT JOIN HR_DEPT D ON D.DEPT_CD = E.DEPT_CD
                      </select>
                      <update id="mssqlUpdateJoin">
                        UPDATE E SET E.EMP_NM = #{nm}
                          FROM HR_EMP E JOIN HR_DEPT D ON D.DEPT_CD = E.DEPT_CD
                         WHERE D.DEPT_NM = #{deptNm}
                      </update>
                    """, List.of("mssqlUpdateJoin"));
            case "POSTGRES" -> new Case("postgres", url, user, pw,
                    List.of("DROP VIEW IF EXISTS V_EMP_DEPT", "DROP TABLE IF EXISTS HR_EMP", "DROP TABLE IF EXISTS HR_DEPT"),
                    List.of("CREATE TABLE HR_DEPT (DEPT_CD VARCHAR(10) PRIMARY KEY, DEPT_NM VARCHAR(100), UPPER_DEPT_CD VARCHAR(10))",
                            "COMMENT ON TABLE HR_DEPT IS '부서'",
                            "COMMENT ON COLUMN HR_DEPT.DEPT_NM IS '부서명'",
                            "CREATE TABLE HR_EMP (EMP_NO VARCHAR(10) PRIMARY KEY, EMP_NM VARCHAR(50), "
                                    + "DEPT_CD VARCHAR(10) REFERENCES HR_DEPT(DEPT_CD), SAL_AMT NUMERIC(12,2), HIRE_DT DATE)",
                            "COMMENT ON TABLE HR_EMP IS '사원'",
                            "COMMENT ON COLUMN HR_EMP.EMP_NM IS '성명'",
                            "CREATE VIEW V_EMP_DEPT AS SELECT E.EMP_NO, D.DEPT_NM FROM HR_EMP E JOIN HR_DEPT D ON D.DEPT_CD = E.DEPT_CD"),
                    """
                      <select id="pgPage">
                        SELECT E.EMP_NO, COALESCE(D.DEPT_NM, '-') AS DEPT_NM, E.HIRE_DT::TEXT AS HIRE
                          FROM HR_EMP E LEFT JOIN HR_DEPT D ON D.DEPT_CD = E.DEPT_CD
                         ORDER BY E.EMP_NO LIMIT 10 OFFSET 0
                      </select>
                      <insert id="pgUpsert">
                        INSERT INTO HR_EMP (EMP_NO, EMP_NM) VALUES (#{empNo}, #{nm})
                        ON CONFLICT (EMP_NO) DO UPDATE SET EMP_NM = EXCLUDED.EMP_NM
                      </insert>
                    """, List.of("pgUpsert"));
            case "MYSQL" -> new Case("mysql", url, user, pw,
                    List.of("DROP VIEW IF EXISTS V_EMP_DEPT", "DROP TABLE IF EXISTS HR_EMP", "DROP TABLE IF EXISTS HR_DEPT"),
                    List.of("CREATE TABLE HR_DEPT (DEPT_CD VARCHAR(10) PRIMARY KEY, DEPT_NM VARCHAR(100) COMMENT '부서명', "
                                    + "UPPER_DEPT_CD VARCHAR(10)) COMMENT='부서'",
                            "CREATE TABLE HR_EMP (EMP_NO VARCHAR(10) PRIMARY KEY, EMP_NM VARCHAR(50) COMMENT '성명', DEPT_CD VARCHAR(10), "
                                    + "SAL_AMT DECIMAL(12,2), HIRE_DT DATE, FOREIGN KEY (DEPT_CD) REFERENCES HR_DEPT(DEPT_CD)) COMMENT='사원'",
                            "CREATE VIEW V_EMP_DEPT AS SELECT E.EMP_NO, D.DEPT_NM FROM HR_EMP E JOIN HR_DEPT D ON D.DEPT_CD = E.DEPT_CD"),
                    """
                      <select id="mysqlPage">
                        SELECT E.EMP_NO, IFNULL(D.DEPT_NM, '-') AS DEPT_NM, DATE_FORMAT(E.HIRE_DT, '%Y%m%d') AS HIRE_YMD
                          FROM HR_EMP E LEFT JOIN HR_DEPT D ON D.DEPT_CD = E.DEPT_CD   # 부서 없는 사원 포함
                         LIMIT 0, 10
                      </select>
                      <insert id="mysqlUpsert">
                        INSERT INTO HR_EMP (EMP_NO, EMP_NM) VALUES (#{empNo}, #{nm})
                        ON DUPLICATE KEY UPDATE EMP_NM = VALUES(EMP_NM)
                      </insert>
                    """, List.of("mysqlUpsert"));
            default -> throw new IllegalArgumentException(db);
        };
    }

    /** 스키마 준비용 접속 URL (MySQL 은 내장 MariaDB 드라이버로 준비하고, 인덱싱은 jdbc:mysql: 그대로 → 드라이버 자동 다운로드 경로) */
    private static String setupUrl(Case c) {
        if (c.url().startsWith("jdbc:mysql:")) return "jdbc:mariadb:" + c.url().substring("jdbc:mysql:".length());
        return c.url();
    }

    private static void prepareSchema(Case c) throws Exception {
        // SQL Server: master 의 시스템 객체와 섞이지 않게 테스트 전용 DB 를 만든다
        java.util.regex.Matcher db = java.util.regex.Pattern.compile("databaseName=([\\w]+)").matcher(c.url());
        if (c.url().startsWith("jdbc:sqlserver:") && db.find()) {
            try (Connection conn = DriverManager.getConnection(c.url().replace("databaseName=" + db.group(1), "databaseName=master"),
                    c.user(), c.password()); Statement st = conn.createStatement()) {
                st.execute("IF DB_ID('" + db.group(1) + "') IS NULL CREATE DATABASE [" + db.group(1) + "]");
            }
        }
        try (Connection conn = DriverManager.getConnection(setupUrl(c), c.user(), c.password());
             Statement st = conn.createStatement()) {
            for (String d : c.drops()) {
                try {
                    st.execute(d);
                } catch (Exception ignore) {
                    // 처음 실행이면 없음
                }
            }
            for (String s : c.ddl()) st.execute(s);
        }
    }

    private static boolean has(String out, String s) {
        return out.toLowerCase(Locale.ROOT).contains(s.toLowerCase(Locale.ROOT));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("databases")
    void schemaDialectSqlAndImpact(Case c) throws Exception {
        assumeTrue(c != null, "SQLGRAPH_IT_<DB>_URL 환경변수가 없어 실제 DB 테스트를 건너뜀");
        prepareSchema(c);

        Path project = Files.createTempDirectory("sqlgraph-it-" + c.name());
        String pwEnv = "SQLGRAPH_IT_" + c.name().toUpperCase(Locale.ROOT) + "_PASSWORD";
        Files.writeString(project.resolve("sqlgraph.yml"), """
                databases:
                  - id: it
                    type: jdbc
                    url: "%s"
                    user: "%s"
                    passwordEnv: %s
                """.formatted(c.url(), c.user(), pwEnv));
        Path mapper = project.resolve("src/main/resources/mapper/EmpMapper.xml");
        Files.createDirectories(mapper.getParent());
        Files.writeString(mapper, "<mapper namespace=\"it.EmpMapper\">\n" + COMMON_MAPPER + c.dialectMapper() + "</mapper>\n");

        try (Workspace ws = new Workspace(new SqlGraphHome(home.resolve(c.name())), project, s -> System.out.println("[" + c.name() + "] " + s))) {
            Tools tools = new Tools(ws, om);
            Workspace.Indexed r = ws.index(project, null, false);
            Indexer.Report rep = r.outcome().report();
            System.out.println("[" + c.name() + "] " + rep);
            assertEquals(1, rep.databases(), "DB 접속·메타데이터 수집: " + rep.warnings());
            assertTrue(rep.tables() >= 3, "테이블 2 + 뷰 1: " + rep.tables());
            assertEquals(0, rep.failed(), c.name() + " 방언 SQL 은 모두 파싱되어야 함: "
                    + tools.call("index_status", om.readTree("{\"failures\":20}")));

            String desc = tools.call("describe_table", om.readTree("{\"table\":\"HR_EMP\"}"));
            assertTrue(has(desc, "EMP_NM") && has(desc, "성명"), "컬럼 코멘트: " + desc);
            assertTrue(has(desc, "사원"), "테이블 코멘트: " + desc);
            assertTrue(has(desc, "HR_EMP.DEPT_CD → HR_DEPT.DEPT_CD") && has(desc, "FK"), "FK: " + desc);
            assertTrue(has(desc, "| PK"), "PK: " + desc);

            String search = tools.call("search_table", om.readTree("{\"keyword\":\"부서\"}"));
            assertTrue(has(search, "HR_DEPT"), "한글 코멘트로 검색: " + search);
            assertTrue(has(tools.call("search_table", om.readTree("{\"keyword\":\"V_EMP\"}")), "V_EMP_DEPT"), "뷰");

            String impact = tools.call("impact_analysis", om.readTree("{\"table\":\"HR_EMP\",\"column\":\"DEPT_CD\"}"));
            String sure = impact.substring(0, impact.contains("### 사용 가능성") ? impact.indexOf("### 사용 가능성") : impact.length());
            assertTrue(sure.contains("it.EmpMapper.selectEmp"), impact);

            String nm = tools.call("impact_analysis", om.readTree("{\"table\":\"HR_EMP\",\"column\":\"EMP_NM\"}"));
            for (String w : c.writeEmpNm()) assertTrue(nm.contains("it.EmpMapper." + w), "방언 쓰기 쿼리 " + w + ": " + nm);
        }
    }
}
