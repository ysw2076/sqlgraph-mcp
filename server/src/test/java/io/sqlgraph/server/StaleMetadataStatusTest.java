package io.sqlgraph.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DB 에 접속하지 못한 채 다시 인덱싱하면 index_project 결과와 index_status 에
 * 어떤 DB 가 이전 메타데이터를 쓰고 있는지 한 줄로 보이고, describe_table 은 계속 쓸 수 있다.
 * (처음에는 DDL 파일로 읽고, 다음에는 같은 id 를 아무도 듣지 않는 포트의 DB 로 바꿔 접속이 실패하게 만든다)
 */
class StaleMetadataStatusTest {

    @TempDir
    Path tmp;

    final ObjectMapper om = new ObjectMapper();
    Workspace ws;

    static final String DDL_CONFIG = """
            project: .
            databases:
              - id: app
                type: ddl
                files: ["schema.sql"]
            sources:
              include: ["src/*.sql"]
            """;

    static final String UNREACHABLE_CONFIG = """
            project: .
            databases:
              - id: app
                type: jdbc
                url: "jdbc:mariadb://127.0.0.1:1/app?connectTimeout=2000"
                user: test
                password: test
            sources:
              include: ["src/*.sql"]
            """;

    @AfterEach
    void tearDown() throws Exception {
        if (ws != null) ws.close();
    }

    @Test
    void reindexWithoutDbShowsWhichDatabaseUsesPreviousMetadata() throws Exception {
        Path project = tmp.resolve("stale-demo");
        Files.createDirectories(project.resolve("src"));
        Files.writeString(project.resolve("schema.sql"),
                "CREATE TABLE EMP (EMP_NO VARCHAR(10) PRIMARY KEY, EMP_NM VARCHAR(50));\n");
        Files.writeString(project.resolve("src/q.sql"), "SELECT EMP_NM FROM EMP WHERE EMP_NO = ?;\n");
        Files.writeString(project.resolve("sqlgraph.yml"), DDL_CONFIG);

        ws = new Workspace(new SqlGraphHome(tmp.resolve("home")), tmp, s -> { });
        Tools tools = new Tools(ws, om);

        String first = tools.indexProject(project.toString(), null, false);
        assertTrue(first.contains("- 테이블 1, 컬럼 2"), first);
        assertFalse(first.contains(Tools.STALE_LABEL), first);
        assertFalse(status(tools).contains(Tools.STALE_LABEL));

        // 같은 DB 에 접속할 수 없게 된다. 소스에는 쿼리가 하나 늘었다
        Files.writeString(project.resolve("sqlgraph.yml"), UNREACHABLE_CONFIG);
        Files.writeString(project.resolve("src/q.sql"),
                "SELECT EMP_NM FROM EMP WHERE EMP_NO = ?;\nSELECT EMP_NO FROM EMP;\n");

        String second = tools.indexProject(project.toString(), null, false);
        assertTrue(second.contains("- 테이블 1, 컬럼 2"), "테이블이 0 으로 덮어써지지 않음: " + second);
        assertTrue(second.contains("- " + Tools.STALE_LABEL + "app (테이블 1개, 마지막 성공 "), second);
        assertTrue(second.contains("이전 메타데이터 유지"), "확인 필요에 경고: " + second);
        assertTrue(second.contains("- 쿼리 2건"), "소스 쿼리는 새로 반영: " + second);

        String st = status(tools);
        assertTrue(st.contains("- 테이블 1 / 컬럼 2"), st);
        assertTrue(st.contains("- " + Tools.STALE_LABEL + "app (테이블 1개, 마지막 성공 "), st);

        String d = tools.call("describe_table", om.readTree("{\"table\":\"EMP\",\"project\":\"stale-demo\"}"));
        assertTrue(d.contains("EMP_NM"), d);

        // DB 가 돌아오면 표시가 사라진다
        Files.writeString(project.resolve("sqlgraph.yml"), DDL_CONFIG);
        String back = tools.indexProject(project.toString(), null, false);
        assertFalse(back.contains(Tools.STALE_LABEL), back);
        assertFalse(status(tools).contains(Tools.STALE_LABEL));
    }

    private String status(Tools tools) throws Exception {
        return tools.call("index_status", om.readTree("{\"project\":\"stale-demo\"}"));
    }
}
