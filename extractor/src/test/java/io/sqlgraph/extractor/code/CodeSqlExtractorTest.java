package io.sqlgraph.extractor.code;

import io.sqlgraph.core.analyze.SqlAnalyzer;
import io.sqlgraph.core.model.Access;
import io.sqlgraph.core.model.AnalyzedSql;
import io.sqlgraph.core.model.ColumnRef;
import io.sqlgraph.core.model.ExtractedSql;
import io.sqlgraph.core.model.SqlKind;
import io.sqlgraph.core.model.TableRef;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CodeSqlExtractorTest {

    private final SqlAnalyzer analyzer = new SqlAnalyzer();

    private static Map<String, ExtractedSql> run(CodeSqlExtractor x, String path, String src) {
        Map<String, ExtractedSql> m = new LinkedHashMap<>();
        for (ExtractedSql e : x.extract(path, src)) m.put(e.ownerId(), e);
        return m;
    }

    private static Map<String, ExtractedSql> run(String path, String src) {
        CodeSqlExtractor x = new CodeSqlExtractor();
        x.prepare(path, src);
        return run(x, path, src);
    }

    private Set<String> tables(ExtractedSql e) {
        return analyzer.analyze(e.sql()).tables().stream().map(TableRef::name).collect(Collectors.toSet());
    }

    private Set<String> columns(AnalyzedSql a) {
        return a.columns().stream().map(c -> c.table() + "." + c.column()).collect(Collectors.toSet());
    }

    // ================================================================= Java

    @Test
    void javaConcatenationStringBuilderAndOwners() {
        String src = """
                package com.acme.emp;

                public class EmpDao {
                    private final JdbcTemplate jdbc;

                    public List<Emp> findByDept(String dept) {
                        String sql = "SELECT e.EMP_NO, e.EMP_NM " +
                                     "FROM HR_EMP e " +
                                     "WHERE e.DEPT_CD = ?";
                        return jdbc.query(sql, mapper, dept);
                    }

                    public void search(String nm, String order) {
                        StringBuilder sb = new StringBuilder();
                        sb.append("SELECT EMP_NO FROM HR_EMP WHERE 1=1");
                        if (nm != null) sb.append(" AND EMP_NM LIKE ?");
                        sb.append(" ORDER BY ").append(order);
                        jdbc.query(sb.toString(), mapper);
                    }

                    public int retire(String no) {
                        return jdbc.update("UPDATE HR_EMP SET RETIRE_YN = 'Y' WHERE EMP_NO = " + no);
                    }

                    void log() {
                        logger.info("Select a file from disk");
                        throw new IllegalStateException("Update failed for user");
                        String m = "delete from cache";
                    }
                }
                """;
        Map<String, ExtractedSql> m = run("src/main/java/com/acme/emp/EmpDao.java", src);
        assertEquals(Set.of("com.acme.emp.EmpDao.findByDept", "com.acme.emp.EmpDao.search", "com.acme.emp.EmpDao.retire"),
                m.keySet(), "영문 문장(Select a file…, Update failed…)은 SQL 이 아님");

        ExtractedSql find = m.get("com.acme.emp.EmpDao.findByDept");
        assertEquals("SELECT e.EMP_NO, e.EMP_NM FROM HR_EMP e WHERE e.DEPT_CD = ?", find.sql());
        assertFalse(find.dynamic());
        assertEquals(7, find.location().line());
        assertEquals(SqlKind.SELECT, find.kind());

        ExtractedSql search = m.get("com.acme.emp.EmpDao.search");
        assertEquals("SELECT EMP_NO FROM HR_EMP WHERE 1=1 AND EMP_NM LIKE ? ORDER BY ${order}", search.sql());
        assertTrue(search.dynamic());

        ExtractedSql retire = m.get("com.acme.emp.EmpDao.retire");
        assertEquals("UPDATE HR_EMP SET RETIRE_YN = 'Y' WHERE EMP_NO = ${no}", retire.sql());
        AnalyzedSql a = analyzer.analyze(retire.sql());
        assertTrue(a.parsed(), a.error());
        assertTrue(a.tables().contains(new TableRef(null, "HR_EMP", Access.WRITE)));
        assertTrue(a.columns().contains(new ColumnRef("HR_EMP", "RETIRE_YN", Access.WRITE)));
    }

    @Test
    void skipsTestSources() {
        CodeSqlExtractor x = new CodeSqlExtractor();
        assertFalse(x.supports("src/test/java/a/ADaoTest.java"));
        assertFalse(x.supports("src/main/java/a/ADaoTest.java"));
        assertFalse(x.supports("web/app.spec.ts"));
        assertFalse(x.supports("app/test_dao.py"));
        assertFalse(x.supports("static/js/vendor.min.js"));
        assertTrue(x.supports("src/main/java/a/ADao.java"));
        assertTrue(x.supports("src/main/resources/application-local.yml"));
        assertTrue(x.extract("src/main/resources/application.yml", "spring:\n  datasource:\n").isEmpty());
    }

    @Test
    void myBatisAnnotationMapper() {
        String src = """
                package com.acme.mapper;

                @Mapper
                public interface DeptMapper {
                    @Select("SELECT DEPT_CD, DEPT_NM FROM HR_DEPT WHERE USE_YN = 'Y'")
                    List<Dept> selectAll();

                    @Update({"<script>",
                             "UPDATE HR_DEPT",
                             "<set><if test='nm != null'>DEPT_NM = #{nm},</if></set>",
                             "WHERE DEPT_CD = #{cd}",
                             "</script>"})
                    int updateDept(Dept d);
                }
                """;
        Map<String, ExtractedSql> m = run("src/main/java/com/acme/mapper/DeptMapper.java", src);
        assertEquals(Set.of("com.acme.mapper.DeptMapper.selectAll", "com.acme.mapper.DeptMapper.updateDept"), m.keySet());
        ExtractedSql upd = m.get("com.acme.mapper.DeptMapper.updateDept");
        assertEquals(SqlKind.UPDATE, upd.kind());
        AnalyzedSql a = analyzer.analyze(upd.sql());
        assertTrue(a.parsed(), upd.sql() + " → " + a.error());
        assertTrue(a.columns().contains(new ColumnRef("HR_DEPT", "DEPT_NM", Access.WRITE)));
    }

    // ================================================================== JPA

    private static final String ENTITIES = """
            package com.acme.domain;

            @MappedSuperclass
            public abstract class BaseEntity {
                @Column(name = "REG_DT")
                private LocalDateTime createdAt;
            }

            @Entity
            @Table(name = "HR_EMP")
            public class Employee extends BaseEntity {
                @Id
                @Column(name = "EMP_NO")
                private String empNo;

                @Column(name = "EMP_NM", length = 50)
                private String name;

                private String email;

                @ManyToOne(fetch = FetchType.LAZY)
                @JoinColumn(name = "DEPT_CD")
                private Department dept;

                @OneToMany(mappedBy = "employee")
                private List<Appointment> appointments = new ArrayList<>();

                @Transient
                private String temp;

                public String getName() {
                    return name;
                }
            }

            @Entity
            @Table(name = "HR_DEPT")
            class Department {
                @Id @Column(name = "DEPT_CD") private String code;
                @Column(name = "DEPT_NM") private String name;
            }

            @Entity(name = "Appointment")
            @Table(name = "HR_APPNT")
            class Appointment {
                @Id @Column(name = "APPNT_SEQ") private Long seq;
                @ManyToOne @JoinColumn(name = "EMP_NO") private Employee employee;
                @Column(name = "APPNT_DT") private LocalDate date;
            }

            @Entity
            class PayItem {
                @Id private Long payItemId;
                private BigDecimal unitAmount;
            }
            """;

    private static final String REPOSITORY = """
            package com.acme.repo;

            public interface EmployeeRepository extends JpaRepository<Employee, String> {

                @Query("SELECT e FROM Employee e JOIN e.dept d WHERE d.name = :deptName AND e.createdAt > :since")
                List<Employee> findByDeptName(@Param("deptName") String deptName, @Param("since") LocalDateTime since);

                @Query(value = "SELECT * FROM HR_EMP WHERE DEPT_CD = ?1", nativeQuery = true)
                List<Employee> nativeByDept(String dept);

                @Modifying
                @Query("update Employee e set e.email = :email where e.empNo = :no")
                int updateEmail(@Param("email") String email, @Param("no") String no);

                @Query("SELECT new com.acme.dto.EmpDto(e.empNo, e.dept.name) FROM Employee e WHERE e.dept.code = :c")
                List<EmpDto> dtos(@Param("c") String c);

                @Query("select a from Appointment a where a.employee.empNo = ?1 order by a.date desc")
                List<Appointment> history(String empNo);

                @Query("SELECT p.unitAmount FROM PayItem p WHERE p.payItemId = :id")
                BigDecimal unit(Long id);
            }
            """;

    private Map<String, ExtractedSql> jpa() {
        CodeSqlExtractor x = new CodeSqlExtractor();
        x.prepare("src/main/java/com/acme/domain/Entities.java", ENTITIES);
        x.prepare("src/main/java/com/acme/repo/EmployeeRepository.java", REPOSITORY);
        assertTrue(x.extract("src/main/java/com/acme/domain/Entities.java", ENTITIES).isEmpty());
        return run(x, "src/main/java/com/acme/repo/EmployeeRepository.java", REPOSITORY);
    }

    @Test
    void jpqlIsTranslatedToTablesAndColumns() {
        Map<String, ExtractedSql> m = jpa();
        String p = "com.acme.repo.EmployeeRepository.";
        assertEquals(Set.of(p + "findByDeptName", p + "nativeByDept", p + "updateEmail", p + "dtos", p + "history", p + "unit"),
                m.keySet());

        AnalyzedSql find = analyzer.analyze(m.get(p + "findByDeptName").sql());
        assertTrue(find.parsed(), m.get(p + "findByDeptName").sql() + " → " + find.error());
        assertEquals(Set.of("HR_EMP", "HR_DEPT"), tables(m.get(p + "findByDeptName")));
        assertTrue(columns(find).containsAll(Set.of("HR_DEPT.DEPT_NM", "HR_EMP.REG_DT")), columns(find).toString());
        assertTrue(find.selectsAll(), "SELECT e → 엔티티 전체 컬럼");
        assertTrue(find.joins().stream().anyMatch(j -> j.leftColumn().equals("DEPT_CD") && j.rightColumn().equals("DEPT_CD")),
                "경로 조인 e.dept → ON e.DEPT_CD = d.DEPT_CD");
        assertTrue(m.get(p + "findByDeptName").sql().contains("/* JPQL: SELECT e FROM Employee e"), "원래 JPQL 도 보여줌");

        ExtractedSql nat = m.get(p + "nativeByDept");
        assertEquals("SELECT * FROM HR_EMP WHERE DEPT_CD = ?1", nat.sql(), "네이티브 쿼리는 그대로");

        AnalyzedSql upd = analyzer.analyze(m.get(p + "updateEmail").sql());
        assertTrue(upd.parsed(), upd.error());
        assertTrue(upd.columns().contains(new ColumnRef("HR_EMP", "email", Access.WRITE)), upd.columns().toString());
        assertTrue(upd.columns().contains(new ColumnRef("HR_EMP", "EMP_NO", Access.READ)));

        AnalyzedSql dto = analyzer.analyze(m.get(p + "dtos").sql());
        assertTrue(dto.parsed(), m.get(p + "dtos").sql() + " → " + dto.error());
        assertTrue(columns(dto).containsAll(Set.of("HR_EMP.EMP_NO", "HR_DEPT.DEPT_NM", "HR_EMP.DEPT_CD")), columns(dto).toString());

        AnalyzedSql hist = analyzer.analyze(m.get(p + "history").sql());
        assertTrue(hist.parsed(), hist.error());
        assertEquals(Set.of("HR_APPNT"), tables(m.get(p + "history")));
        assertTrue(columns(hist).containsAll(Set.of("HR_APPNT.EMP_NO", "HR_APPNT.APPNT_DT")), columns(hist).toString());

        AnalyzedSql unit = analyzer.analyze(m.get(p + "unit").sql());
        assertEquals(Set.of("pay_item"), tables(m.get(p + "unit")), "이름 없는 엔티티 → snake_case 테이블");
        assertTrue(columns(unit).containsAll(Set.of("pay_item.unit_amount", "pay_item.pay_item_id")), columns(unit).toString());
    }

    @Test
    void physicalNamingStrategyKeepsNames() {
        CodeSqlExtractor x = new CodeSqlExtractor();
        x.prepare("src/main/resources/application.yml",
                "spring:\n  jpa:\n    hibernate:\n      naming:\n        physical-strategy: org.hibernate.boot.model.naming.PhysicalNamingStrategyStandardImpl\n");
        x.prepare("src/main/java/com/acme/domain/Entities.java", ENTITIES);
        Map<String, ExtractedSql> m = run(x, "src/main/java/com/acme/repo/EmployeeRepository.java", REPOSITORY);
        assertEquals(Set.of("PayItem"), tables(m.get("com.acme.repo.EmployeeRepository.unit")));
    }

    @Test
    void createQueryAndNativeQuery() {
        String src = """
                package com.acme.svc;
                public class EmpService {
                    public List<Employee> active() {
                        return em.createQuery("select e from Employee e where e.email is not null", Employee.class).getResultList();
                    }
                    public List<Object[]> raw() {
                        return em.createNativeQuery("SELECT EMP_NO FROM HR_EMP WHERE EMAIL IS NULL").getResultList();
                    }
                }
                """;
        CodeSqlExtractor x = new CodeSqlExtractor();
        x.prepare("Entities.java", ENTITIES);
        Map<String, ExtractedSql> m = run(x, "src/main/java/com/acme/svc/EmpService.java", src);
        ExtractedSql active = m.get("com.acme.svc.EmpService.active");
        assertNotNull(active, m.keySet().toString());
        assertTrue(active.sql().startsWith("select e.* from HR_EMP e where e.email is not null"), active.sql());
        assertEquals("SELECT EMP_NO FROM HR_EMP WHERE EMAIL IS NULL", m.get("com.acme.svc.EmpService.raw").sql());
    }

    // ============================================================ 다른 언어

    @Test
    void kotlin() {
        String src = "package com.acme.k\n\n"
                + "class OrderDao(private val jdbc: JdbcTemplate) {\n"
                + "    fun find(id: Long): Order? {\n"
                + "        val sql = \"\"\"\n"
                + "            SELECT o.ORDER_ID, o.AMOUNT\n"
                + "              FROM ORDERS o\n"
                + "             WHERE o.ORDER_ID = ?\n"
                + "        \"\"\".trimIndent()\n"
                + "        return jdbc.queryForObject(sql, mapper, id)\n"
                + "    }\n\n"
                + "    fun byStatus(status: String) = jdbc.query(\"SELECT * FROM ORDERS WHERE STATUS = '$status'\", mapper)\n"
                + "}\n";
        Map<String, ExtractedSql> m = run("src/main/kotlin/com/acme/k/OrderDao.kt", src);
        assertEquals(Set.of("com.acme.k.OrderDao.find", "com.acme.k.OrderDao.byStatus"), m.keySet());
        assertEquals("SELECT o.ORDER_ID, o.AMOUNT FROM ORDERS o WHERE o.ORDER_ID = ?", m.get("com.acme.k.OrderDao.find").sql());
        assertTrue(m.get("com.acme.k.OrderDao.byStatus").dynamic(), "Kotlin 문자열 템플릿");
    }

    @Test
    void python() {
        String src = """
                def get_emp(cur, emp_no):
                    cur.execute("SELECT EMP_NO, EMP_NM FROM HR_EMP WHERE EMP_NO = %s", (emp_no,))


                class EmpRepo:
                    def search(self, dept):
                        sql = ("SELECT EMP_NO "
                               "FROM HR_EMP "
                               "WHERE DEPT_CD = %(dept)s")
                        sql += " ORDER BY EMP_NO"
                        return self.db.execute(sql, {"dept": dept})

                    def count(self, table):
                        return self.db.execute(f"SELECT COUNT(*) FROM {table}")
                """;
        Map<String, ExtractedSql> m = run("app/dao/emp_repo.py", src);
        assertEquals(Set.of("emp_repo.get_emp", "emp_repo.EmpRepo.search", "emp_repo.EmpRepo.count"), m.keySet());
        assertEquals("SELECT EMP_NO, EMP_NM FROM HR_EMP WHERE EMP_NO = ?", m.get("emp_repo.get_emp").sql());
        assertEquals("SELECT EMP_NO FROM HR_EMP WHERE DEPT_CD = ? ORDER BY EMP_NO", m.get("emp_repo.EmpRepo.search").sql());
        assertTrue(m.get("emp_repo.EmpRepo.count").dynamic(), "f-string");
        assertTrue(analyzer.analyze(m.get("emp_repo.EmpRepo.search").sql()).parsed());
    }

    @Test
    void javascriptTemplatesAndTaggedSql() {
        String src = """
                import { sql } from 'slonik';
                export async function listOrders(db, status, since) {
                  const rows = await db.query(`SELECT id, total FROM orders WHERE status = $1 AND created_at > ${since}`, [status]);
                  const one = await pool.query(sql`SELECT * FROM users WHERE id = ${id}`);
                  return rows;
                }
                """;
        Map<String, ExtractedSql> m = run("src/db/orders.ts", src);
        assertEquals(2, m.size(), m.keySet().toString());
        ExtractedSql first = m.get("orders.listOrders");
        assertEquals("SELECT id, total FROM orders WHERE status = ? AND created_at > ${since}", first.sql());
        assertTrue(first.dynamic());
        ExtractedSql tagged = m.get("orders.listOrders#2");
        assertEquals("SELECT * FROM users WHERE id = ?", tagged.sql());
        assertFalse(tagged.dynamic(), "sql`` 태그의 ${} 는 바인드 변수");
    }

    @Test
    void goCSharpPhpRuby() {
        String go = """
                package store

                func (s *Store) GetUser(id int) (*User, error) {
                    row := s.db.QueryRow(`SELECT id, name FROM users WHERE id = $1`, id)
                    return scan(row)
                }
                """;
        assertEquals(Set.of("store.GetUser"), run("internal/store/user.go", go).keySet());

        String cs = """
                namespace Acme.Data
                {
                    public class EmpRepository
                    {
                        public Emp Get(int id)
                        {
                            var sql = @"SELECT EmpNo, Name
                                        FROM dbo.Employees
                                        WHERE EmpNo = @id";
                            return conn.QuerySingle<Emp>(sql, new { id });
                        }
                    }
                }
                """;
        Map<String, ExtractedSql> c = run("Data/EmpRepository.cs", cs);
        assertEquals(Set.of("Acme.Data.EmpRepository.Get"), c.keySet());
        assertTrue(tables(c.get("Acme.Data.EmpRepository.Get")).contains("Employees"));

        String php = """
                <?php
                namespace App\\Repo;

                class UserRepo {
                    public function find($id) {
                        $sql = "SELECT * FROM users WHERE id = " . $id;
                        $sql .= " AND deleted_at IS NULL";
                        return $this->db->query($sql);
                    }
                }
                """;
        Map<String, ExtractedSql> p = run("app/Repo/UserRepo.php", php);
        assertEquals("SELECT * FROM users WHERE id = ${$id} AND deleted_at IS NULL", p.get("App.Repo.UserRepo.find").sql());

        String rb = """
                class Report
                  def monthly
                    sql = <<~SQL
                      SELECT dept_cd, SUM(amount) FROM sales GROUP BY dept_cd
                    SQL
                    ActiveRecord::Base.connection.execute(sql)
                  end
                end
                """;
        Map<String, ExtractedSql> r = run("app/models/report.rb", rb);
        assertEquals(Set.of("report.Report.monthly"), r.keySet());
        assertEquals(Set.of("sales"), tables(r.get("report.Report.monthly")));
    }

    @Test
    void lexerSkipsCommentsAndCharLiterals() {
        List<CodeLexer.Tok> t = CodeLexer.lex("""
                // "SELECT * FROM NOT_THIS"
                /* "SELECT * FROM NOR_THIS" */
                char q = '"';
                String s = "SELECT 1 FROM T";
                """, CodeLexer.Lang.JAVA);
        List<String> strs = t.stream().filter(x -> x.type() == CodeLexer.T.STR).map(CodeLexer.Tok::text).toList();
        assertEquals(List.of("SELECT 1 FROM T"), strs);
    }
}
