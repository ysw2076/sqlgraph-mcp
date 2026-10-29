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
}
