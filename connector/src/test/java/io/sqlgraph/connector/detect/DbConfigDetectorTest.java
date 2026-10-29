package io.sqlgraph.connector.detect;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DbConfigDetectorTest {

    @TempDir
    Path dir;

    private void write(String rel, String content) throws Exception {
        Path f = dir.resolve(rel);
        Files.createDirectories(f.getParent());
        Files.writeString(f, content);
    }

    private DbConfigDetector detector(Map<String, String> env) {
        return new DbConfigDetector(env::get);
    }

    @Test
    void springYamlWithProfilesPrefersLocal() throws Exception {
        write("hr-web/src/main/resources/application.yml", """
                spring:
                  profiles:
                    active: local
                  datasource:
                    driver-class-name: org.mariadb.jdbc.Driver
                    username: app
                ---
                spring:
                  config:
                    activate:
                      on-profile: local
                  datasource:
                    url: jdbc:log4jdbc:mariadb://localhost:3306/hr
                    password: ${DB_PW:localpw}
                ---
                spring:
                  config:
                    activate:
                      on-profile: prod
                  datasource:
                    url: jdbc:mariadb://prod-db:3306/hr
                    password: ENC(abcd)
                """);
        var r = detector(Map.of()).detect(dir, null);
        assertEquals(2, r.all().size());
        assertEquals(1, r.chosen().size());
        DetectedDb d = r.chosen().get(0);
        assertEquals("local", d.profile());
        assertEquals("jdbc:mariadb://localhost:3306/hr", d.url(), "log4jdbc 접두어 제거");
        assertEquals("app", d.user(), "프로필 없는 문서의 username 상속");
        assertEquals("localpw", d.password(), "${DB_PW:localpw} 기본값");
        assertTrue(d.usable());
        assertTrue(r.all().stream().anyMatch(x -> "prod".equals(x.profile()) && x.problem() != null), "ENC 비밀번호는 문제로 표시");
    }

    @Test
    void propertiesFilesAndEnvPlaceholder() throws Exception {
        write("src/main/resources/application-dev.properties", """
                spring.datasource.url=jdbc:postgresql://${DB_HOST}:5432/app
                spring.datasource.username=dev
                spring.datasource.password=${DB_PW}
                """);
        var r = detector(Map.of("DB_HOST", "pg.local", "DB_PW", "s3cret")).detect(dir, null);
        DetectedDb d = r.chosen().get(0);
        assertEquals("jdbc:postgresql://pg.local:5432/app", d.url());
        assertEquals("s3cret", d.password());
        assertEquals("dev", d.profile());
    }

    @Test
    void unresolvedPlaceholderIsReported() throws Exception {
        write("src/main/resources/application.properties", """
                spring.datasource.url=jdbc:mysql://localhost/app
                spring.datasource.password=${MISSING_PW}
                """);
        var r = detector(Map.of()).detect(dir, null);
        DetectedDb d = r.all().get(0);
        assertTrue(d.problem().contains("MISSING_PW"));
        assertTrue(r.chosen().isEmpty(), "쓸 수 없는 후보는 자동 선택하지 않음");
    }

    @Test
    void multipleDatasourcesAndHikariDedupe() throws Exception {
        write("src/main/resources/application.yml", """
                spring:
                  datasource:
                    hikari:
                      jdbc-url: jdbc:mariadb://localhost/hr
                      username: a
                      password: b
                app:
                  datasource:
                    pay:
                      jdbc-url: jdbc:oracle:thin:@//ora:1521/PAY
                      username: pay
                      password: p
                """);
        var r = detector(Map.of()).detect(dir, null);
        assertEquals(2, r.chosen().size());
        assertTrue(r.chosen().stream().anyMatch(d -> d.url().startsWith("jdbc:oracle")));
    }

    @Test
    void prodOnlyIsSkippedUnlessProfileGiven() throws Exception {
        write("src/main/resources/application-prod.yml", """
                spring.datasource.url: jdbc:mariadb://prod/hr
                spring.datasource.username: u
                spring.datasource.password: p
                """);
        var r = detector(Map.of()).detect(dir, null);
        assertTrue(r.chosen().isEmpty());
        assertTrue(r.notes().stream().anyMatch(n -> n.contains("운영 DB")));
        var r2 = detector(Map.of()).detect(dir, "prod");
        assertEquals(1, r2.chosen().size());
    }

    @Test
    void testResourcesAndInMemoryDbIgnored() throws Exception {
        write("src/test/resources/application.yml", "spring.datasource.url: jdbc:mariadb://test/hr\n");
        write("src/main/resources/application.yml", "spring.datasource.url: jdbc:h2:mem:test\n");
        var r = detector(Map.of()).detect(dir, null);
        assertTrue(r.all().isEmpty());
    }

    @Test
    void dotEnvDatabaseUrlAndLaravelStyle() throws Exception {
        write(".env", """
                # comment
                DATABASE_URL="postgres://app:p%40ss@db.local:5433/shop"
                DB_CONNECTION=mysql
                DB_HOST=127.0.0.1
                DB_PORT=3306
                DB_DATABASE=laravel
                DB_USERNAME=root
                DB_PASSWORD=secret # inline comment
                """);
        var r = detector(Map.of()).detect(dir, null);
        List<DetectedDb> c = r.chosen();
        assertEquals(2, c.size());
        DetectedDb pg = c.stream().filter(d -> d.url().contains("postgresql")).findFirst().orElseThrow();
        assertEquals("jdbc:postgresql://db.local:5433/shop", pg.url());
        assertEquals("p@ss", pg.password());
        DetectedDb my = c.stream().filter(d -> d.url().contains("mysql")).findFirst().orElseThrow();
        assertEquals("jdbc:mysql://127.0.0.1:3306/laravel", my.url());
        assertEquals("secret", my.password());
    }

    @Test
    void describeNeverShowsPassword() {
        DetectedDb d = new DetectedDb("g", null, "jdbc:sqlserver://h;password=xyz", "u", "pw123", "a.yml", null);
        assertTrue(!d.describe().contains("pw123") && !d.describe().contains("xyz"));
        assertNull(DetectedDb.maskUrl(null));
    }
}
