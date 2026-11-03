package io.sqlgraph.extractor;

import io.sqlgraph.core.model.ExtractedSql;
import io.sqlgraph.core.model.SqlKind;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MyBatisXmlExtractorTest {

    private final MyBatisXmlExtractor x = new MyBatisXmlExtractor();

    private Map<String, ExtractedSql> run(String xml) {
        return x.extract("m.xml", xml).stream().collect(Collectors.toMap(ExtractedSql::ownerId, Function.identity()));
    }

    @Test
    void flattensDynamicTags() {
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN" "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
                <mapper namespace="a.B">
                  <sql id="cols">EMP_NO, EMP_NM</sql>
                  <select id="list">
                    SELECT <include refid="cols"/> FROM HR_EMP
                    <where>
                      <if test="x">AND DEPT_CD = #{x}</if>
                      <if test="y">AND EMP_NO IN <foreach collection="y" item="i" open="(" separator="," close=")">#{i}</foreach></if>
                    </where>
                  </select>
                  <update id="upd">
                    UPDATE HR_EMP <set><if test="a">EMP_NM = #{a},</if><if test="b">EMAIL = #{b},</if></set> WHERE EMP_NO = #{id}
                  </update>
                  <select id="trimmed">
                    SELECT 1 FROM T <trim prefix="WHERE" prefixOverrides="AND |OR ">AND A = 1</trim>
                  </select>
                  <select id="choice">
                    SELECT A FROM T WHERE 1=1 <choose><when test="p">AND B = 1</when><otherwise>AND C = 2</otherwise></choose>
                  </select>
                  <select id="cmp"><![CDATA[ SELECT A FROM T WHERE A < 10 ]]></select>
                </mapper>""";
        Map<String, ExtractedSql> m = run(xml);
        assertEquals(5, m.size());
        assertEquals("SELECT EMP_NO, EMP_NM FROM HR_EMP WHERE DEPT_CD = #{x} AND EMP_NO IN (#{i})", m.get("a.B.list").sql());
        assertEquals("UPDATE HR_EMP SET EMP_NM = #{a}, EMAIL = #{b} WHERE EMP_NO = #{id}", m.get("a.B.upd").sql());
        assertEquals("SELECT 1 FROM T WHERE A = 1", m.get("a.B.trimmed").sql());
        assertEquals("SELECT A FROM T WHERE 1=1 AND B = 1", m.get("a.B.choice").sql());
        assertEquals("SELECT A FROM T WHERE A < 10", m.get("a.B.cmp").sql());
        assertEquals(SqlKind.UPDATE, m.get("a.B.upd").kind());
        assertFalse(m.get("a.B.list").dynamic());
        assertEquals(5, m.get("a.B.list").location().line());
    }

    @Test
    void dollarAndExternalIncludeAreDynamic() {
        Map<String, ExtractedSql> m = run("""
                <mapper namespace="n">
                  <select id="a">SELECT * FROM T ORDER BY ${col}</select>
                  <select id="b">SELECT * FROM T <include refid="other.Mapper.paging"/></select>
                </mapper>""");
        assertTrue(m.get("n.a").dynamic());
        assertTrue(m.get("n.b").dynamic());
    }

    @Test
    void ibatisSqlMap() {
        Map<String, ExtractedSql> m = run("""
                <sqlMap namespace="Legacy">
                  <select id="get">
                    SELECT EMP_NO FROM HR_EMP
                    <dynamic prepend="WHERE">
                      <isNotEmpty prepend="AND" property="d">DEPT_CD = #d#</isNotEmpty>
                      <isNotEmpty prepend="AND" property="ids">EMP_NO IN <iterate property="ids" open="(" close=")" conjunction=",">#ids[]#</iterate></isNotEmpty>
                    </dynamic>
                  </select>
                  <statement id="raw">DELETE FROM HR_EMP WHERE EMP_NO = #id#</statement>
                </sqlMap>""");
        assertEquals("SELECT EMP_NO FROM HR_EMP WHERE DEPT_CD = #d# AND EMP_NO IN (#ids[]#)", m.get("Legacy.get").sql());
        assertEquals(null, m.get("Legacy.raw").kind());
    }

    @Test
    void ignoresNonMapperXml() {
        List<ExtractedSql> r = x.extract("logback.xml", "<configuration><root level=\"INFO\"/></configuration>");
        assertTrue(r.isEmpty());
    }

    @Test
    void includeFromOtherMapperFile() {
        MyBatisXmlExtractor ex = new MyBatisXmlExtractor();
        String common = """
                <mapper namespace="com.Common">
                  <sql id="empCols">${alias}.EMP_NO, ${alias}.EMP_NM, <include refid="deptCol"/></sql>
                  <sql id="deptCol">${alias}.DEPT_CD</sql>
                  <sql id="paging">LIMIT #{size} OFFSET #{offset}</sql>
                </mapper>""";
        String emp = """
                <mapper namespace="com.Emp">
                  <select id="list">
                    SELECT <include refid="com.Common.empCols"><property name="alias" value="e"/></include>
                    FROM HR_EMP e <include refid="com.Common.paging"/>
                  </select>
                  <select id="byId">SELECT * FROM HR_EMP <include refid="paging"/></select>
                  <select id="missing">SELECT * FROM HR_EMP <include refid="nope.Mapper.x"/></select>
                </mapper>""";
        ex.prepare("common.xml", common);
        ex.prepare("emp.xml", emp);
        Map<String, ExtractedSql> m = ex.extract("emp.xml", emp).stream()
                .collect(Collectors.toMap(ExtractedSql::ownerId, Function.identity()));
        assertEquals("SELECT e.EMP_NO, e.EMP_NM, e.DEPT_CD FROM HR_EMP e LIMIT #{size} OFFSET #{offset}",
                m.get("com.Emp.list").sql());
        assertFalse(m.get("com.Emp.list").dynamic());
        // namespace 없이 id 만 적어도 프로젝트에 하나뿐이면 찾는다
        assertEquals("SELECT * FROM HR_EMP LIMIT #{size} OFFSET #{offset}", m.get("com.Emp.byId").sql());
        assertTrue(m.get("com.Emp.missing").dynamic());
    }

    @Test
    void chooseBranchesBecomeVariants() {
        Map<String, ExtractedSql> m = run("""
                <mapper namespace="n">
                  <select id="search">
                    SELECT A FROM
                    <choose>
                      <when test="t == 'emp'">HR_EMP</when>
                      <when test="t == 'dept'">HR_DEPT</when>
                      <otherwise>HR_RETIRE</otherwise>
                    </choose>
                    WHERE 1=1
                    <choose>
                      <when test="x">AND B = 1</when>
                      <otherwise>AND C = 2</otherwise>
                    </choose>
                  </select>
                  <select id="plain">SELECT A FROM T</select>
                </mapper>""");
        ExtractedSql s = m.get("n.search");
        assertEquals("SELECT A FROM HR_EMP WHERE 1=1 AND B = 1", s.sql());
        assertEquals(List.of("SELECT A FROM HR_DEPT WHERE 1=1 AND C = 2",
                "SELECT A FROM HR_RETIRE WHERE 1=1 AND C = 2"), s.variants());
        assertTrue(m.get("n.plain").variants().isEmpty());
    }

    @Test
    void nestedChooseInsideLaterBranch() {
        Map<String, ExtractedSql> m = run("""
                <mapper namespace="n">
                  <select id="q">
                    SELECT 1 FROM
                    <choose>
                      <when test="a">T1</when>
                      <otherwise><choose><when test="b">T2</when><when test="c">T3</when><otherwise>T4</otherwise></choose></otherwise>
                    </choose>
                  </select>
                </mapper>""");
        ExtractedSql s = m.get("n.q");
        assertEquals("SELECT 1 FROM T1", s.sql());
        assertTrue(s.variants().contains("SELECT 1 FROM T3"), s.variants().toString());
        assertTrue(s.variants().contains("SELECT 1 FROM T4"), s.variants().toString());
    }

    @Test
    void lineCommentDoesNotSwallowRestOfSql() {
        String xml = """
                <mapper namespace="a.C">
                  <select id="withComment">
                    SELECT E.EMP_NO   -- 사번
                         , D.DEPT_NM  -- 부서명 (it's ok)
                      FROM HR_EMP E
                      -- 부서 조인
                      JOIN HR_DEPT D ON D.DEPT_CD = E.DEPT_CD
                     WHERE E.MEMO != '--not a comment'
                  </select>
                </mapper>""";
        ExtractedSql e = run(xml).get("a.C.withComment");
        assertTrue(e.sql().contains("/* 사번 */"), "주석은 보이게 남김: " + e.sql());
        assertTrue(e.sql().contains("'--not a comment'"), "문자열 안의 -- 는 그대로");
        var a = new io.sqlgraph.core.analyze.SqlAnalyzer().analyze(e.sql());
        assertTrue(a.parsed(), a.error());
        assertEquals(java.util.Set.of("HR_EMP", "HR_DEPT"),
                a.tables().stream().map(io.sqlgraph.core.model.TableRef::name).collect(Collectors.toSet()),
                "-- 주석 뒤의 FROM/JOIN 이 빠지면 안 됨");
    }

    @Test
    void mutuallyExclusiveIfsBecomeBranches() {
        String xml = """
                <mapper namespace="a.D">
                  <sql id="orgTree">
                    WITH RECURSIVE ORG_TREE AS (
                    <if test='includeSub != null and includeSub == "Y"'>
                      SELECT CO, ORG FROM ORG_M WHERE CO = #{co}
                      UNION ALL
                      SELECT B.CO, B.ORG FROM ORG_TREE A JOIN ORG_M B ON A.ORG = B.ORG_UP
                    </if>
                    <if test="includeSub == null or includeSub == ''">
                      SELECT CO, ORG FROM ORG_ONLY WHERE CO = #{co}
                    </if>
                    )
                  </sql>
                  <select id="chart">
                    <include refid="orgTree"/>
                    SELECT E.ORG, COUNT(*) FROM EMP E JOIN ORG_TREE T ON T.ORG = E.ORG GROUP BY E.ORG
                  </select>
                </mapper>""";
        ExtractedSql e = run(xml).get("a.D.chart");
        assertFalse(e.sql().contains("ORG_ONLY"), "본 SQL 은 첫 분기만: " + e.sql());
        assertEquals(1, e.variants().size());
        assertTrue(e.variants().get(0).contains("ORG_ONLY"));
        var an = new io.sqlgraph.core.analyze.SqlAnalyzer();
        assertTrue(an.analyze(e.sql()).parsed(), an.analyze(e.sql()).error());
        assertTrue(an.analyze(e.variants().get(0)).parsed());
    }

    @Test
    void independentIfsStayTogether() {
        String xml = """
                <mapper namespace="a.E">
                  <select id="list">
                    SELECT A FROM T WHERE 1=1
                    <if test="x != null">AND X = #{x}</if>
                    <if test="y != null">AND Y = #{y}</if>
                  </select>
                </mapper>""";
        ExtractedSql e = run(xml).get("a.E.list");
        assertTrue(e.sql().contains("X = #{x}") && e.sql().contains("Y = #{y}"), e.sql());
        assertTrue(e.variants().isEmpty());
    }

    @Test
    void complementaryNullChecksAreExclusive() {
        String xml = """
                <mapper namespace="a.F">
                  <delete id="del">
                    DELETE FROM TASK_LOG WHERE ID NOT IN (SELECT MIN(ID) FROM TASK_LOG WHERE TASK_ID = #{taskId}
                      <if test="step != null and step != ''">AND STEP = #{step}</if>
                      <if test="step == null or step == ''">AND STEP IS NULL</if>
                    )
                  </delete>
                  <select id="mixedOr">
                    SELECT A FROM T WHERE 1=1
                    <if test="x == 'A' or y == 'B'">AND P = 1</if>
                    <if test="x == 'C'">AND Q = 2</if>
                  </select>
                </mapper>""";
        var m = run(xml);
        assertEquals(1, m.get("a.F.del").variants().size(), m.get("a.F.del").sql());
        assertTrue(m.get("a.F.mixedOr").variants().isEmpty(), "다른 변수를 or 로 엮으면 배타적이라고 볼 수 없음");
        assertTrue(m.get("a.F.mixedOr").sql().contains("P = 1") && m.get("a.F.mixedOr").sql().contains("Q = 2"));
    }
}
