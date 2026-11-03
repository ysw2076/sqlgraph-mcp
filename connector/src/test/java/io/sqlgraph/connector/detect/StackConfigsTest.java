package io.sqlgraph.connector.detect;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Spring Boot 외 기술 스택의 DB 접속 설정 자동 탐지 */
class StackConfigsTest {

    @TempDir
    Path dir;

    private void write(String rel, String content) throws Exception {
        Path f = dir.resolve(rel);
        Files.createDirectories(f.getParent());
        Files.writeString(f, content);
    }

    private DbConfigDetector.Result detect(Map<String, String> env) throws Exception {
        return new DbConfigDetector(env::get).detect(dir, null);
    }

    private static DetectedDb only(List<DetectedDb> list) {
        assertEquals(1, list.size(), list.toString());
        return list.get(0);
    }

    @Test
    void djangoSettingsWithEnvAndProfiles() throws Exception {
        write("mysite/settings/base.py", "INSTALLED_APPS = []\n");
        write("mysite/settings/local.py", """
                from .base import *
                DATABASES = {
                    'default': {
                        'ENGINE': 'django.db.backends.postgresql',
                        'NAME': 'hr',
                        'USER': os.environ.get('DB_USER', 'app'),
                        'PASSWORD': os.getenv('DB_PASSWORD'),
                        'HOST': 'localhost',
                        'PORT': '5432',
                    }
                }
                """);
        write("mysite/settings/production.py", """
                DATABASES = {'default': {'ENGINE': 'django.db.backends.postgresql', 'NAME': 'hr', 'HOST': 'prod-db'}}
                """);
        var r = detect(Map.of("DB_PASSWORD", "secret"));
        DetectedDb d = only(r.chosen());
        assertEquals("jdbc:postgresql://localhost:5432/hr", d.url());
        assertEquals("app", d.user(), "os.environ.get 기본값");
        assertEquals("secret", d.password());
        assertEquals("local", d.profile());
        assertTrue(r.all().stream().anyMatch(x -> "production".equals(x.profile())), "운영 설정도 찾지만 고르지 않음");
    }

    @Test
    void prismaReadsDotEnv() throws Exception {
        write(".env", "DATABASE_URL=\"mysql://app:pw@db.local:3306/shop\"\n");
        write("prisma/schema.prisma", """
                datasource db {
                  provider = "mysql"
                  url      = env("DATABASE_URL")
                }
                """);
        var r = detect(Map.of());
        DetectedDb d = only(r.chosen());   // .env 의 DATABASE_URL 과 같은 DB 라서 하나로 합쳐짐
        assertEquals("jdbc:mysql://db.local:3306/shop", d.url());
        assertEquals("pw", d.password());
    }

    @Test
    void railsDatabaseYmlWithErb() throws Exception {
        write("config/database.yml", """
                default: &default
                  adapter: postgresql
                  encoding: unicode
                  host: <%= ENV.fetch("DB_HOST") { "localhost" } %>
                  username: <%= ENV['DB_USER'] %>
                  password: <%= ENV['DB_PASSWORD'] %>

                development:
                  <<: *default
                  database: shop_development

                test:
                  <<: *default
                  database: shop_test

                production:
                  <<: *default
                  database: shop_production
                """);
        var r = detect(Map.of("DB_USER", "rails", "DB_PASSWORD", "pw"));
        DetectedDb d = only(r.chosen());
        assertEquals("jdbc:postgresql://localhost/shop_development", d.url());
        assertEquals("rails", d.user());
        assertEquals("development", d.profile());
        assertEquals(3, r.all().size());
    }

    @Test
    void dotnetAppSettings() throws Exception {
        write("Api/appsettings.json", """
                {
                  // 기본
                  "ConnectionStrings": {
                    "Default": "Server=sql01,1433;Database=Hr;User Id=hr_app;Password=pw;TrustServerCertificate=True",
                  },
                  "Logging": { "LogLevel": { "Default": "Information" } }
                }
                """);
        write("Api/appsettings.Production.json", """
                { "ConnectionStrings": { "Default": "Server=prod-sql;Database=Hr;User Id=hr;Password=x" } }
                """);
        write("Worker/appsettings.json", """
                { "ConnectionStrings": { "Pg": "Host=pg.local;Port=5432;Database=jobs;Username=w;Password=p" } }
                """);
        var r = detect(Map.of());
        assertEquals(2, r.chosen().size(), r.chosen().toString());
        DetectedDb sql = r.chosen().stream().filter(x -> x.url().startsWith("jdbc:sqlserver")).findFirst().orElseThrow();
        assertEquals("jdbc:sqlserver://sql01:1433;databaseName=Hr;encrypt=false", sql.url());
        assertEquals("hr_app", sql.user());
        DetectedDb pg = r.chosen().stream().filter(x -> x.url().startsWith("jdbc:postgresql")).findFirst().orElseThrow();
        assertEquals("jdbc:postgresql://pg.local:5432/jobs", pg.url());
    }

    @Test
    void dotnetIntegratedSecurityIsReported() {
        DetectedDb d = StackConfigs.adoNet("g", null,
                "Data Source=.\\SQLEXPRESS;Initial Catalog=Hr;Integrated Security=True", "a.json");
        assertNotNull(d.problem());
        assertTrue(d.url().startsWith("jdbc:sqlserver://localhost\\SQLEXPRESS;databaseName=Hr"), d.url());
    }

    @Test
    void sequelizeAndTypeorm() throws Exception {
        write("api/config/config.json", """
                {
                  "development": { "username": "dev", "password": "pw", "database": "app_dev", "host": "127.0.0.1", "dialect": "mysql" },
                  "production":  { "use_env_variable": "DATABASE_URL" }
                }
                """);
        write("svc/ormconfig.json", """
                { "type": "postgres", "host": "localhost", "port": 5432, "username": "u", "password": "p", "database": "svc" }
                """);
        var r = detect(Map.of());
        assertEquals(2, r.chosen().size(), r.chosen().toString());
        assertTrue(r.chosen().stream().anyMatch(x -> x.url().equals("jdbc:mysql://127.0.0.1/app_dev")));
        assertTrue(r.chosen().stream().anyMatch(x -> x.url().equals("jdbc:postgresql://localhost:5432/svc")));
    }

    @Test
    void legacyJavaPropertiesAndSpringXml() throws Exception {
        // eGovFrame globals.properties + Spring XML ${} 치환
        write("src/main/resources/egovframework/egovProps/globals.properties", """
                Globals.DbType = oracle
                Globals.oracle.DriverClassName=oracle.jdbc.OracleDriver
                Globals.oracle.Url=jdbc:oracle:thin:@devdb:1521:ORCL
                Globals.oracle.UserName=hr
                Globals.oracle.Password=hrpw
                """);
        write("src/main/resources/config/prod/jdbc.properties", """
                jdbc.url=jdbc:mariadb://prod-db:3306/hr
                jdbc.username=hr
                jdbc.password=prodpw
                """);
        write("src/main/webapp/WEB-INF/config/context-datasource.xml", """
                <beans>
                  <bean id="dataSource" class="org.apache.commons.dbcp2.BasicDataSource" destroy-method="close">
                    <property name="driverClassName" value="${Globals.oracle.DriverClassName}"/>
                    <property name="url" value="${Globals.oracle.Url}" />
                    <property name="username" value="${Globals.oracle.UserName}"/>
                    <property name="password" value="${Globals.oracle.Password}"/>
                  </bean>
                </beans>
                """);
        write("src/main/resources/mapper/EmpMapper.xml", "<mapper namespace=\"a\"><select id=\"x\">SELECT 1 FROM DUAL</select></mapper>");
        var r = detect(Map.of());
        assertEquals(1, r.chosen().size(), "같은 URL 은 하나로, prod 경로는 건너뜀: " + r.chosen());
        DetectedDb d = r.chosen().get(0);
        assertEquals("jdbc:oracle:thin:@devdb:1521:ORCL", d.url());
        assertEquals("hr", d.user());
        assertEquals("hrpw", d.password());
        assertTrue(r.all().stream().anyMatch(x -> "prod".equals(x.profile())));
    }

    @Test
    void persistenceXmlHibernateCfgAndTomcatResource() throws Exception {
        write("src/main/resources/META-INF/persistence.xml", """
                <persistence>
                  <persistence-unit name="hr">
                    <properties>
                      <property name="jakarta.persistence.jdbc.url" value="jdbc:postgresql://localhost:5432/hr"/>
                      <property name="jakarta.persistence.jdbc.user" value="hr"/>
                      <property name="jakarta.persistence.jdbc.password" value="pw"/>
                    </properties>
                  </persistence-unit>
                </persistence>
                """);
        write("conf/hibernate.cfg.xml", """
                <hibernate-configuration><session-factory>
                  <property name="hibernate.connection.url">jdbc:mysql://localhost:3306/legacy</property>
                  <property name="hibernate.connection.username">leg</property>
                </session-factory></hibernate-configuration>
                """);
        write("src/main/webapp/META-INF/context.xml", """
                <Context>
                  <Resource name="jdbc/hr" auth="Container" type="javax.sql.DataSource"
                            url="jdbc:sqlserver://mssql:1433;databaseName=Pay" username="pay" password="pw"/>
                </Context>
                """);
        var r = detect(Map.of());
        assertEquals(3, r.chosen().size(), r.chosen().toString());
        assertTrue(r.chosen().stream().anyMatch(x -> x.url().equals("jdbc:postgresql://localhost:5432/hr") && "hr".equals(x.user())));
        assertTrue(r.chosen().stream().anyMatch(x -> x.url().equals("jdbc:mysql://localhost:3306/legacy") && "leg".equals(x.user())));
        assertTrue(r.chosen().stream().anyMatch(x -> x.url().startsWith("jdbc:sqlserver://mssql:1433") && "pay".equals(x.user())));
    }

    @Test
    void profileFromPath() {
        assertEquals("prod", StackConfigs.profileFromPath("config/prod/jdbc.properties"));
        assertEquals("prod", StackConfigs.profileFromPath("conf/jdbc-real.properties"));
        assertEquals("local", StackConfigs.profileFromPath("src/main/resources/local/db.properties"));
        assertEquals("", StackConfigs.profileFromPath("src/main/resources/jdbc.properties"));
        assertNull(StackConfigs.kindOf("pom.xml", "pom.xml"));
        assertEquals(StackConfigs.Kind.DJANGO, StackConfigs.kindOf("app/settings.py", "settings.py"));
    }
}
