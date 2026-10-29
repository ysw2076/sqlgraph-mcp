package io.sqlgraph.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.sqlgraph.core.index.Indexer;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 다른 Mapper 파일의 include, choose 의 모든 분기가 인덱스에 반영되는지 */
class MyBatisDynamicIndexTest {

    @Test
    void crossFileIncludeAndChooseBranches() throws Exception {
        Path home = Files.createTempDirectory("sqlgraph-home");
        Path proj = Files.createTempDirectory("mybatis-proj");
        try {
            Path dir = Files.createDirectories(proj.resolve("src/main/resources/mapper"));
            // 조각을 쓰는 파일이 정의 파일보다 먼저 스캔되도록 이름을 정한다
            Files.writeString(dir.resolve("A_EmpMapper.xml"), """
                    <mapper namespace="hr.EmpMapper">
                      <select id="list">
                        SELECT <include refid="hr.Common.cols"><property name="a" value="e"/></include>
                        FROM HR_EMP e <include refid="hr.Common.deptJoin"/>
                      </select>
                      <select id="search">
                        SELECT X FROM
                        <choose>
                          <when test="t == 'emp'">HR_EMP</when>
                          <otherwise>HR_RETIRE</otherwise>
                        </choose>
                      </select>
                    </mapper>""");
            Files.writeString(dir.resolve("Z_Common.xml"), """
                    <mapper namespace="hr.Common">
                      <sql id="cols">${a}.EMP_NO, d.DEPT_NM</sql>
                      <sql id="deptJoin">JOIN HR_DEPT d ON d.DEPT_CD = e.DEPT_CD</sql>
                    </mapper>""");

            ObjectMapper om = new ObjectMapper();
            try (Workspace ws = new Workspace(new SqlGraphHome(home), proj, s -> { })) {
                Workspace.Indexed r = ws.index(proj, null, true);
                Indexer.Report rep = r.outcome().report();
                assertEquals(2, rep.statements());
                assertEquals(0, rep.failed());
                assertEquals(0, rep.dynamic(), "조각을 모두 찾았으므로 동적 쿼리가 아님");

                Tools tools = new Tools(ws, om);
                String dept = tools.call("impact_analysis", om.readTree("{\"table\":\"HR_DEPT\"}"));
                assertTrue(dept.contains("EmpMapper.list"), "다른 파일 조각의 JOIN 테이블이 잡혀야 함:\n" + dept);

                String retire = tools.call("impact_analysis", om.readTree("{\"table\":\"HR_RETIRE\"}"));
                assertTrue(retire.contains("EmpMapper.search"), "choose 의 otherwise 분기 테이블도 잡혀야 함:\n" + retire);
            }
        } finally {
            for (Path p : new Path[]{proj, home}) {
                try (var s = Files.walk(p)) {
                    s.sorted(Comparator.reverseOrder()).forEach(x -> x.toFile().delete());
                }
            }
        }
    }
}
