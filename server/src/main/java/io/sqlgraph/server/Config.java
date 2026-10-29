package io.sqlgraph.server;

import org.yaml.snakeyaml.Yaml;

import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * sqlgraph.yml 설정. 상대 경로는 설정 파일이 있는 폴더 기준.
 *
 * <pre>
 * project: ../my-project
 * index: .sqlgraph/graph.db          # 생략 시 &lt;project&gt;/.sqlgraph/graph.db
 * databases:
 *   - id: hr
 *     type: jdbc
 *     url: jdbc:mariadb://localhost:3306/hr
 *     user: reader
 *     passwordEnv: HR_DB_PW
 *   - id: legacy
 *     type: ddl
 *     files: ["ddl/*.sql"]
 *     dialect: oracle
 * sources:
 *   include: ["**&#47;*.xml", "**&#47;*.sql"]
 *   exclude: ["**&#47;test/**"]
 * </pre>
 */
public record Config(Path file, Path baseDir, Path project, Path index, List<Db> databases,
                     List<String> include, List<String> exclude, long maxFileBytes) {

    public record Db(String id, String type, String url, String user, String password, String passwordEnv,
                     String driverJar, String driverClass, String schema, List<String> files, String dialect,
                     Map<String, String> properties) {}

    public static final List<String> DEFAULT_INCLUDE = List.of("**/*.xml", "**/*.sql");

    @SuppressWarnings("unchecked")
    public static Config load(Path file) throws Exception {
        Path abs = file.toAbsolutePath().normalize();
        Map<String, Object> root;
        try (Reader r = Files.newBufferedReader(abs, StandardCharsets.UTF_8)) {
            Object o = new Yaml().load(r);
            root = o instanceof Map ? (Map<String, Object>) o : Map.of();
        }
        Path base = abs.getParent();
        Path project = base.resolve(str(root.get("project"), ".")).normalize();
        Path index = root.get("index") != null
                ? base.resolve(str(root.get("index"), null)).normalize()
                : project.resolve(".sqlgraph/graph.db");

        List<Db> dbs = new ArrayList<>();
        Object dbList = root.get("databases");
        if (dbList instanceof List<?> l) {
            int n = 0;
            for (Object o : l) {
                if (!(o instanceof Map<?, ?> m)) continue;
                Map<String, Object> d = (Map<String, Object>) m;
                n++;
                String type = str(d.get("type"), d.get("url") != null ? "jdbc" : "ddl").toLowerCase();
                Map<String, String> props = new LinkedHashMap<>();
                if (d.get("properties") instanceof Map<?, ?> pm) {
                    pm.forEach((k, v) -> props.put(String.valueOf(k), String.valueOf(v)));
                }
                dbs.add(new Db(str(d.get("id"), "db" + n), type, str(d.get("url"), null), str(d.get("user"), null),
                        str(d.get("password"), null), str(d.get("passwordEnv"), null), str(d.get("driverJar"), null),
                        str(d.get("driverClass"), null), str(d.get("schema"), null), strList(d.get("files")),
                        str(d.get("dialect"), null), props));
            }
        }

        List<String> include = DEFAULT_INCLUDE;
        List<String> exclude = List.of();
        long maxBytes = 5L * 1024 * 1024;
        if (root.get("sources") instanceof Map<?, ?> sm) {
            Map<String, Object> s = (Map<String, Object>) sm;
            if (s.get("include") != null) include = strList(s.get("include"));
            if (s.get("exclude") != null) exclude = strList(s.get("exclude"));
            if (s.get("maxFileKb") instanceof Number num) maxBytes = num.longValue() * 1024;
        }
        return new Config(abs, base, project, index, dbs, include, exclude, maxBytes);
    }

    /** 설정 파일 없이 프로젝트 폴더만으로 실행할 때 (DB 없이 소스만 인덱싱) */
    public static Config ofProject(Path project) {
        Path p = project.toAbsolutePath().normalize();
        return new Config(null, p, p, p.resolve(".sqlgraph/graph.db"), List.of(), DEFAULT_INCLUDE, List.of(),
                5L * 1024 * 1024);
    }

    private static String str(Object o, String def) {
        return o == null ? def : String.valueOf(o);
    }

    private static List<String> strList(Object o) {
        List<String> r = new ArrayList<>();
        if (o instanceof List<?> l) {
            for (Object x : l) r.add(String.valueOf(x));
        } else if (o != null) {
            r.add(String.valueOf(o));
        }
        return r;
    }
}
