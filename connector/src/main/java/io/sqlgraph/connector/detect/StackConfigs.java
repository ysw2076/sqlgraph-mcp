package io.sqlgraph.connector.detect;

import io.sqlgraph.core.TextFiles;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Spring Boot / .env 외의 설정 파일에서 DB 접속 정보를 찾는다. 파일은 읽기만 한다.
 *
 * <ul>
 *   <li>Django settings.py (settings/local.py …): DATABASES = {'default': {'ENGINE': .., 'NAME': ..}}</li>
 *   <li>Prisma schema.prisma: datasource { provider, url = env("DATABASE_URL") }</li>
 *   <li>Rails config/database.yml (ERB 의 ENV[..] / ENV.fetch 해석)</li>
 *   <li>.NET appsettings*.json: ConnectionStrings (ADO.NET 연결 문자열)</li>
 *   <li>Sequelize config/config.json, TypeORM ormconfig.json</li>
 *   <li>Java 레거시 *.properties (jdbc.url / Globals.Url …)와 XML (Spring bean, persistence.xml,
 *       hibernate.cfg.xml, mybatis-config.xml, Tomcat context.xml)</li>
 * </ul>
 *
 * <p>프로필은 파일명·경로·환경 키에서 정한다 (local / dev / development 는 자동 선택, prod·test 등은 건너뜀).
 */
final class StackConfigs {

    private StackConfigs() {}

    /** 이름 → 값. 프로세스 환경변수, 없으면 프로젝트의 .env 값 */
    interface Lookup extends Function<String, String> {}

    // ====================================================== 파일 종류 판단

    enum Kind { DJANGO, PRISMA, RAILS, DOTNET, SEQUELIZE, TYPEORM, PROPERTIES, XML }

    private static final Pattern SPRING_FILE = Pattern.compile("application(-[\\w.]+)?\\.(ya?ml|properties)");

    static Kind kindOf(String rel, String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        String lrel = rel.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".py") && (lower.startsWith("settings") || lrel.contains("/settings/") || lrel.startsWith("settings/"))) {
            return Kind.DJANGO;
        }
        if (lower.equals("schema.prisma")) return Kind.PRISMA;
        if (lower.equals("database.yml") && (lrel.endsWith("config/database.yml"))) return Kind.RAILS;
        if (lower.startsWith("appsettings") && lower.endsWith(".json")) return Kind.DOTNET;
        if (lower.equals("config.json") && lrel.endsWith("config/config.json")) return Kind.SEQUELIZE;
        if (lower.equals("ormconfig.json")) return Kind.TYPEORM;
        if (lower.endsWith(".properties") && !SPRING_FILE.matcher(name).matches()) return Kind.PROPERTIES;
        if (lower.endsWith(".xml") && !lower.equals("pom.xml") && !lower.endsWith("mapper.xml")
                && !lower.startsWith("logback") && !lower.startsWith("log4j")) return Kind.XML;
        return null;
    }

    static List<DetectedDb> read(Kind kind, Path root, Path file, String rel, Lookup lookup) throws IOException {
        String text = TextFiles.read(file);
        return switch (kind) {
            case DJANGO -> django(text, rel, lookup);
            case PRISMA -> prisma(text, rel, file.getParent(), lookup);
            case RAILS -> rails(text, rel, root, lookup);
            case DOTNET -> dotnet(text, rel);
            case SEQUELIZE -> sequelize(text, rel, lookup);
            case TYPEORM -> typeorm(text, rel);
            case PROPERTIES -> properties(text, rel, lookup);
            case XML -> xml(text, rel, lookup);
        };
    }

    // ======================================================== 프로필

    /** 파일명/환경 이름 → 프로필. 기본 설정은 "" */
    static String profileOf(String name) {
        if (name == null) return "";
        String n = name.toLowerCase(Locale.ROOT);
        if (n.isEmpty() || n.equals("settings") || n.equals("base") || n.equals("common") || n.equals("default")
                || n.equals("appsettings") || n.equals("config")) return "";
        if (n.equals("local") || n.equals("locals")) return "local";
        if (n.equals("dev") || n.equals("develop")) return "dev";
        if (n.equals("development")) return "development";
        return n;   // prod, production, test, staging … → 자동 선택 안 함
    }

    private static final Set<String> PROD_TOKENS = Set.of("prod", "production", "prd", "real", "live", "운영", "release");
    private static final Set<String> NON_DEV_TOKENS = Set.of("test", "tests", "stg", "stage", "staging", "qa", "uat");

    /** 경로·파일명 조각으로 프로필 추정 (레거시 properties / XML) */
    static String profileFromPath(String rel) {
        String[] parts = rel.toLowerCase(Locale.ROOT).split("[/._\\-]+");
        for (String p : parts) if (PROD_TOKENS.contains(p)) return "prod";
        for (String p : parts) if (NON_DEV_TOKENS.contains(p)) return p;
        for (String p : parts) if (p.equals("local")) return "local";
        for (String p : parts) if (p.equals("dev") || p.equals("develop") || p.equals("development")) return "dev";
        return "";
    }

    private static String nullIfBlank(String p) {
        return p == null || p.isEmpty() ? null : p;
    }

    // ======================================================== Django

    private static final Pattern PY_ENV = Pattern.compile(
            "(?:os\\.environ\\.get|os\\.getenv|env\\.str|env|config|getenv)\\(\\s*['\"]([\\w.]+)['\"]\\s*(?:,\\s*(?:default\\s*=\\s*)?['\"]([^'\"]*)['\"])?[^)]*\\)"
                    + "|os\\.environ\\[\\s*['\"]([\\w.]+)['\"]\\s*]");

    static List<DetectedDb> django(String text, String rel, Lookup lookup) {
        List<DetectedDb> out = new ArrayList<>();
        int at = text.indexOf("DATABASES");
        if (at < 0) return out;
        int open = text.indexOf('{', at);
        if (open < 0) return out;
        int close = matchBrace(text, open);
        String block = text.substring(open + 1, Math.max(open + 1, close));
        String stem = rel.substring(rel.lastIndexOf('/') + 1).replaceFirst("\\.py$", "");
        String profile = profileOf(stem);
        // 'default': { ... }, 'legacy': { ... }
        Matcher alias = Pattern.compile("['\"](\\w+)['\"]\\s*:\\s*\\{").matcher(block);
        int from = 0;
        while (alias.find(from)) {
            int bo = alias.end() - 1;
            int bc = matchBrace(block, bo);
            String body = block.substring(bo + 1, Math.max(bo + 1, bc));
            from = Math.max(alias.end(), bc);
            Map<String, String> kv = pyDict(body, lookup);
            String engine = kv.get("ENGINE");
            String scheme = engine == null ? null : DbConfigDetector.scheme(engine.replace("django.db.backends.", "")
                    .replace("postgresql_psycopg2", "postgresql").replace("mssql", "sqlserver"));
            String group = "django:" + dirOf(rel.replaceFirst("/settings/[^/]*$", "/settings.py")) + ":" + alias.group(1);
            if (engine != null && engine.contains("sqlite3")) {
                out.add(new DetectedDb(group, nullIfBlank(profile), null, null, null, rel,
                        "SQLite 경로가 코드로 지정되어 있음 — sqlgraph.yml 로 지정"));
                continue;
            }
            if (scheme == null) continue;
            String problem = kv.containsKey("__unresolved__") ? kv.get("__unresolved__") : null;
            out.add(new DetectedDb(group, nullIfBlank(profile),
                    DbConfigDetector.jdbcUrl(scheme, kv.get("HOST"), blankNull(kv.get("PORT")), kv.get("NAME")),
                    kv.get("USER"), kv.get("PASSWORD"), rel, problem));
        }
        return out;
    }

    /** 'KEY': 'value' / 'KEY': os.environ.get('X', 'def') 형태만 읽는다 */
    private static Map<String, String> pyDict(String body, Lookup lookup) {
        Map<String, String> kv = new LinkedHashMap<>();
        // 값: os.environ.get('X', 'd') 같은 호출(인자 안 콤마 포함) 또는 콤마 전까지
        Matcher m = Pattern.compile("['\"](\\w+)['\"]\\s*:\\s*([\\w.\\[\\]'\"]+\\([^)]*\\)|[^,\\n}]+)").matcher(body);
        while (m.find()) {
            String key = m.group(1);
            String raw = m.group(2).strip();
            String v = pyValue(raw, lookup);
            if (v == null && PY_ENV.matcher(raw).find()) {
                kv.merge("__unresolved__", key + " 값을 환경변수에서 찾을 수 없음", (a, b) -> a + ", " + b);
            }
            if (v != null) kv.put(key, v);
        }
        return kv;
    }

    private static String pyValue(String raw, Lookup lookup) {
        Matcher lit = Pattern.compile("^[rbuf]?['\"]([^'\"]*)['\"]").matcher(raw);
        if (lit.find()) return lit.group(1);
        Matcher num = Pattern.compile("^(\\d+)").matcher(raw);
        if (num.find()) return num.group(1);
        Matcher e = PY_ENV.matcher(raw);
        if (e.find()) {
            String key = e.group(1) != null ? e.group(1) : e.group(3);
            String v = lookup.apply(key);
            return v != null ? v : e.group(2);
        }
        return null;
    }

    // ======================================================== Prisma

    static List<DetectedDb> prisma(String text, String rel, Path dir, Lookup lookup) {
        List<DetectedDb> out = new ArrayList<>();
        Matcher ds = Pattern.compile("datasource\\s+(\\w+)\\s*\\{([^}]*)}").matcher(text);
        while (ds.find()) {
            String body = ds.group(2);
            String provider = find(body, "provider\\s*=\\s*\"([^\"]+)\"");
            String url = find(body, "\\burl\\s*=\\s*\"([^\"]+)\"");
            String envKey = find(body, "\\burl\\s*=\\s*env\\(\\s*\"([^\"]+)\"\\s*\\)");
            String group = "prisma:" + dirOf(rel) + ":" + ds.group(1);
            if (url == null && envKey != null) url = lookup.apply(envKey);
            if (url == null) {
                out.add(new DetectedDb(group, null, null, null, null, rel,
                        envKey != null ? "환경변수 " + envKey + " 를 찾을 수 없음" : "url 이 없음"));
                continue;
            }
            if ("sqlite".equals(provider) || url.startsWith("file:")) {
                Path p = dir.resolve(url.replaceFirst("^file:", "")).normalize();
                out.add(new DetectedDb(group, null, "jdbc:sqlite:" + p.toString().replace('\\', '/'), null, null, rel, null));
                continue;
            }
            DetectedDb d = DbConfigDetector.fromUrl(group, url, rel);
            if (d != null) out.add(d);
        }
        return out;
    }

    // ========================================================= Rails

    private static final Pattern ERB = Pattern.compile("<%=\\s*(.*?)\\s*%>");
    private static final Pattern RUBY_ENV = Pattern.compile(
            "ENV(?:\\.fetch)?\\s*[\\[(]\\s*['\"]([\\w.]+)['\"]\\s*(?:]|\\)|,\\s*['\"]?([^'\")]*)['\"]?\\s*\\))(?:\\s*\\{\\s*['\"]?([^'\"}]*)['\"]?\\s*})?"
                    + "(?:\\s*\\|\\|\\s*['\"]([^'\"]*)['\"])?");

    @SuppressWarnings("unchecked")
    static List<DetectedDb> rails(String text, String rel, Path root, Lookup lookup) {
        List<DetectedDb> out = new ArrayList<>();
        // ERB 를 먼저 값으로 바꾼다 (못 바꾸면 빈 값)
        Matcher em = ERB.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (em.find()) {
            Matcher r = RUBY_ENV.matcher(em.group(1));
            String v = "";
            if (r.find()) {
                v = lookup.apply(r.group(1));
                if (v == null) v = r.group(2) != null ? r.group(2) : r.group(3) != null ? r.group(3) : r.group(4);
                if (v == null) v = "";
            } else if (em.group(1).matches("['\"].*['\"]")) {
                v = em.group(1).substring(1, em.group(1).length() - 1);
            }
            em.appendReplacement(sb, Matcher.quoteReplacement("\"" + v.replace("\"", "") + "\""));
        }
        em.appendTail(sb);
        Object doc = new Yaml(new SafeConstructor(new LoaderOptions())).load(sb.toString());
        if (!(doc instanceof Map<?, ?> envs)) return out;
        for (var e : ((Map<Object, Object>) envs).entrySet()) {
            String env = String.valueOf(e.getKey());
            if (env.equals("default") || !(e.getValue() instanceof Map<?, ?> cfg0)) continue;
            Map<String, Object> cfg = (Map<String, Object>) cfg0;
            // 다중 DB (primary: {...}, animals: {...})
            boolean multi = cfg.values().stream().anyMatch(v -> v instanceof Map<?, ?> m && m.containsKey("adapter"));
            Map<String, Map<String, Object>> dbs = new LinkedHashMap<>();
            if (multi) {
                cfg.forEach((k, v) -> {
                    if (v instanceof Map<?, ?> m) dbs.put(k, (Map<String, Object>) m);
                });
            } else {
                dbs.put("primary", cfg);
            }
            for (var db : dbs.entrySet()) {
                Map<String, Object> c = db.getValue();
                String group = "rails:" + dirOf(rel) + ":" + db.getKey();
                String profile = nullIfBlank(profileOf(env));
                Object urlObj = c.get("url");
                if (urlObj != null && !String.valueOf(urlObj).isBlank()) {
                    DetectedDb d = DbConfigDetector.fromUrl(group, String.valueOf(urlObj), rel);
                    if (d != null) out.add(new DetectedDb(group, profile, d.url(), d.user(), d.password(), rel, d.problem()));
                    continue;
                }
                String adapter = str(c.get("adapter"));
                if (adapter == null) continue;
                String dbName = str(c.get("database"));
                if (adapter.contains("sqlite")) {
                    if (dbName == null) continue;
                    out.add(new DetectedDb(group, profile,
                            "jdbc:sqlite:" + root.resolve(dbName).normalize().toString().replace('\\', '/'), null, null, rel, null));
                    continue;
                }
                String scheme = DbConfigDetector.scheme(adapter.replace("mysql2", "mysql").replace("trilogy", "mysql")
                        .replace("postgis", "postgresql"));
                if (scheme == null) continue;
                out.add(new DetectedDb(group, profile,
                        DbConfigDetector.jdbcUrl(scheme, str(c.get("host")), str(c.get("port")), dbName),
                        str(c.get("username")), str(c.get("password")), rel, null));
            }
        }
        return out;
    }

    // ========================================================== .NET

    @SuppressWarnings("unchecked")
    static List<DetectedDb> dotnet(String text, String rel) {
        List<DetectedDb> out = new ArrayList<>();
        Object doc = loadJson(text);
        if (!(doc instanceof Map<?, ?> root)) return out;
        Object cs = null;
        for (var e : ((Map<Object, Object>) root).entrySet()) {
            if (String.valueOf(e.getKey()).equalsIgnoreCase("ConnectionStrings")) cs = e.getValue();
        }
        if (!(cs instanceof Map<?, ?> conns)) return out;
        String name = rel.substring(rel.lastIndexOf('/') + 1);
        // appsettings.Development.json → Development (확장자를 먼저 떼야 appsettings.json 이 "" 가 된다)
        String env = name.replaceFirst("(?i)\\.json$", "").replaceFirst("(?i)^appsettings\\.?", "");
        String profile = nullIfBlank(profileOf(env));
        for (var e : ((Map<Object, Object>) conns).entrySet()) {
            String group = "dotnet:" + dirOf(rel) + ":" + e.getKey();
            DetectedDb d = adoNet(group, profile, String.valueOf(e.getValue()), rel);
            if (d != null) out.add(d);
        }
        return out;
    }

    /** ADO.NET 연결 문자열 → JDBC. 공급자는 키 모양으로 추정 */
    static DetectedDb adoNet(String group, String profile, String cs, String rel) {
        Map<String, String> kv = new LinkedHashMap<>();
        for (String part : cs.split(";")) {
            int eq = part.indexOf('=');
            if (eq <= 0) continue;
            kv.put(part.substring(0, eq).strip().toLowerCase(Locale.ROOT).replaceAll("\\s+", " "), part.substring(eq + 1).strip());
        }
        if (kv.isEmpty()) return null;
        String host = first(kv, "server", "data source", "host", "address", "addr", "network address");
        String db = first(kv, "database", "initial catalog");
        String user = first(kv, "user id", "userid", "uid", "username", "user name", "user");
        String pw = first(kv, "password", "pwd");
        String port = first(kv, "port");
        if (host == null) return null;
        String problem = null;
        String scheme;
        if (kv.containsKey("host") || kv.containsKey("username")) scheme = "postgresql";
        else if (kv.containsKey("uid") || "3306".equals(port) || kv.containsKey("sslmode") && !kv.containsKey("trustservercertificate")) {
            scheme = "mysql";
        } else if (host.contains("/") && !host.contains("\\")) scheme = "oracle";
        else scheme = "sqlserver";

        if (scheme.equals("sqlserver")) {
            String h = host.replaceFirst("(?i)^tcp:", "");
            if (h.toLowerCase(Locale.ROOT).startsWith("(localdb)")) {
                return new DetectedDb(group, profile, null, user, pw, rel, "LocalDB 는 JDBC 로 접속할 수 없음 — sqlgraph.yml 로 지정");
            }
            String instance = null;
            if (h.contains(",")) {
                port = h.substring(h.indexOf(',') + 1).strip();
                h = h.substring(0, h.indexOf(','));
            }
            if (h.contains("\\")) {
                instance = h.substring(h.indexOf('\\') + 1);
                h = h.substring(0, h.indexOf('\\'));
            }
            if (h.equals(".")) h = "localhost";
            String integrated = first(kv, "integrated security", "trusted_connection");
            if (integrated != null && (integrated.equalsIgnoreCase("true") || integrated.equalsIgnoreCase("sspi") || integrated.equalsIgnoreCase("yes"))) {
                problem = "Windows 통합 인증은 지원하지 않음 — SQL 계정을 sqlgraph.yml 로 지정";
            }
            String url = "jdbc:sqlserver://" + h + (instance != null ? "\\" + instance : "") + (port != null ? ":" + port : "")
                    + (db != null ? ";databaseName=" + db : "") + ";encrypt=false";
            return new DetectedDb(group, profile, url, user, pw, rel, problem);
        }
        if (scheme.equals("oracle")) {
            String h = host.replaceFirst("^//", "");
            return new DetectedDb(group, profile, "jdbc:oracle:thin:@//" + h, user, pw, rel, null);
        }
        String h = host;
        if (h.contains(":") && port == null) {
            port = h.substring(h.indexOf(':') + 1);
            h = h.substring(0, h.indexOf(':'));
        }
        return new DetectedDb(group, profile, DbConfigDetector.jdbcUrl(scheme, h, port, db), user, pw, rel, null);
    }

    // ===================================================== Sequelize / TypeORM

    @SuppressWarnings("unchecked")
    static List<DetectedDb> sequelize(String text, String rel, Lookup lookup) {
        List<DetectedDb> out = new ArrayList<>();
        Object doc = loadJson(text);
        if (!(doc instanceof Map<?, ?> envs)) return out;
        for (var e : ((Map<Object, Object>) envs).entrySet()) {
            if (!(e.getValue() instanceof Map<?, ?> c0)) continue;
            Map<String, Object> c = (Map<String, Object>) c0;
            String profile = nullIfBlank(profileOf(String.valueOf(e.getKey())));
            String envUrl = str(c.get("use_env_variable"));
            if (envUrl != null) {
                String url = lookup.apply(envUrl);
                if (url != null) {
                    DetectedDb d = DbConfigDetector.fromUrl("sequelize:" + dirOf(rel), url, rel);
                    if (d != null) out.add(new DetectedDb("sequelize:" + dirOf(rel), profile, d.url(), d.user(), d.password(), rel, d.problem()));
                }
                continue;
            }
            String scheme = DbConfigDetector.scheme(str(c.get("dialect")));
            if (scheme == null) continue;
            out.add(new DetectedDb("sequelize:" + dirOf(rel), profile,
                    DbConfigDetector.jdbcUrl(scheme, str(c.get("host")), str(c.get("port")), str(c.get("database"))),
                    str(c.get("username")), str(c.get("password")), rel, null));
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    static List<DetectedDb> typeorm(String text, String rel) {
        List<DetectedDb> out = new ArrayList<>();
        Object doc = loadJson(text);
        List<Object> items = doc instanceof List<?> l ? (List<Object>) l : List.of(doc);
        int n = 0;
        for (Object o : items) {
            if (!(o instanceof Map<?, ?> c0)) continue;
            Map<String, Object> c = (Map<String, Object>) c0;
            n++;
            String group = "typeorm:" + dirOf(rel) + ":" + (c.get("name") != null ? c.get("name") : n);
            if (c.get("url") != null) {
                DetectedDb d = DbConfigDetector.fromUrl(group, String.valueOf(c.get("url")), rel);
                if (d != null) out.add(d);
                continue;
            }
            String scheme = DbConfigDetector.scheme(str(c.get("type")));
            if (scheme == null) continue;
            String db = str(c.get("database"));
            if (scheme.equals("oracle") && c.get("sid") != null) db = str(c.get("sid"));
            out.add(new DetectedDb(group, null, DbConfigDetector.jdbcUrl(scheme, str(c.get("host")), str(c.get("port")), db),
                    str(c.get("username")), str(c.get("password")), rel, null));
        }
        return out;
    }

    // ================================================ Java 레거시 properties / XML

    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([^}:]+)(?::([^}]*))?}");
    private static final Set<String> USER_KEYS = Set.of("username", "user", "userid", "user_id", "user-name", "id", "uid", "usr");
    private static final Set<String> PW_KEYS = Set.of("password", "pwd", "passwd", "pass", "pw");

    /** 같은 접두어의 url / username / password (jdbc.url, Globals.oracle.Url, db.hr.jdbcUrl …) */
    static List<DetectedDb> properties(String text, String rel, Lookup lookup) throws IOException {
        List<DetectedDb> out = new ArrayList<>();
        if (!text.contains("jdbc:")) return out;
        Properties p = new Properties();
        p.load(new StringReader(text));
        Map<String, String> flat = new LinkedHashMap<>();
        for (String k : p.stringPropertyNames()) flat.put(k, p.getProperty(k).strip());
        String profile = nullIfBlank(profileFromPath(rel));
        for (var e : flat.entrySet()) {
            String v = e.getValue();
            if (!v.startsWith("jdbc:")) continue;
            String key = e.getKey();
            int dot = key.lastIndexOf('.');
            String prefix = dot < 0 ? "" : key.substring(0, dot + 1);
            String user = sibling(flat, prefix, USER_KEYS);
            String pw = sibling(flat, prefix, PW_KEYS);
            List<String> problems = new ArrayList<>();
            out.add(new DetectedDb("props:" + rel + "#" + key, profile, resolve(v, flat, lookup, problems),
                    resolve(user, flat, lookup, problems), resolve(pw, flat, lookup, problems), rel,
                    problems.isEmpty() ? null : String.join(", ", problems)));
        }
        return out;
    }

    private static String sibling(Map<String, String> flat, String prefix, Set<String> names) {
        for (var e : flat.entrySet()) {
            String k = e.getKey();
            if (!k.startsWith(prefix)) continue;
            String last = k.substring(prefix.length()).toLowerCase(Locale.ROOT);
            if (names.contains(last) || names.contains(last.replaceAll("^(jdbc|db|database|connection)[._-]?", ""))) return e.getValue();
        }
        return null;
    }

    private static final Pattern XML_PROPERTY = Pattern.compile(
            "<property\\s+name\\s*=\\s*\"([^\"]+)\"\\s+value\\s*=\\s*\"([^\"]*)\"\\s*/?>"
                    + "|<property\\s+name\\s*=\\s*\"([^\"]+)\"\\s*>\\s*([^<]*?)\\s*</property>");
    private static final Pattern RESOURCE = Pattern.compile("<Resource\\b([^>]*)>", Pattern.DOTALL);

    /**
     * 부모 요소(bean / persistence-unit / session-factory / dataSource) 단위로 property 를 묶어 url·user·password 를 찾는다.
     * ${...} 는 같은 프로젝트의 properties 값(placeholders)과 환경변수로 해석한다.
     */
    static List<DetectedDb> xml(String text, String rel, Lookup lookup) {
        List<DetectedDb> out = new ArrayList<>();
        if (!text.contains("jdbc:") && !text.contains("${")) return out;
        if (text.contains("<mapper") || text.contains("<sqlMap")) return out;
        String profile = nullIfBlank(profileFromPath(rel));
        Map<String, String> none = Map.of();

        // Tomcat <Resource url=".." username=".." password=".."/>
        Matcher r = RESOURCE.matcher(text);
        while (r.find()) {
            String attrs = r.group(1);
            String url = attr(attrs, "url");
            if (url == null) continue;
            List<String> problems = new ArrayList<>();
            String u = resolve(url, none, lookup, problems);
            if (u == null || !u.startsWith("jdbc:")) continue;
            out.add(new DetectedDb("xml:" + rel + "#" + attr(attrs, "name"), profile, u,
                    resolve(attr(attrs, "username"), none, lookup, problems),
                    resolve(attr(attrs, "password"), none, lookup, problems), rel,
                    problems.isEmpty() ? null : String.join(", ", problems)));
        }

        // <property name=".." value=".."/> 묶음: 블록(<bean>, <properties>, <dataSource>) 단위로 나눈다
        Matcher block = Pattern.compile("<(bean|properties|dataSource|session-factory|persistence-unit)\\b[^>]*>(.*?)</\\1>",
                Pattern.DOTALL).matcher(text);
        int n = 0;
        while (block.find()) {
            Map<String, String> props = new LinkedHashMap<>();
            Matcher pm = XML_PROPERTY.matcher(block.group(2));
            while (pm.find()) {
                String k = pm.group(1) != null ? pm.group(1) : pm.group(3);
                String v = pm.group(1) != null ? pm.group(2) : pm.group(4);
                props.put(k, v.replace("&amp;", "&"));
            }
            String urlKey = null;
            for (String k : props.keySet()) {
                String lk = k.toLowerCase(Locale.ROOT);
                if (lk.equals("url") || lk.endsWith(".url") || lk.endsWith("jdbcurl") || lk.endsWith("connection.url")) {
                    urlKey = k;
                    break;
                }
            }
            if (urlKey == null) continue;
            List<String> problems = new ArrayList<>();
            String url = resolve(props.get(urlKey), none, lookup, problems);
            if (url == null || !url.startsWith("jdbc:")) continue;
            String user = null;
            String pw = null;
            for (var e : props.entrySet()) {
                String lk = e.getKey().toLowerCase(Locale.ROOT);
                String last = lk.substring(lk.lastIndexOf('.') + 1);
                if (user == null && USER_KEYS.contains(last)) user = e.getValue();
                if (pw == null && PW_KEYS.contains(last)) pw = e.getValue();
            }
            n++;
            out.add(new DetectedDb("xml:" + rel + "#" + n, profile, url, resolve(user, none, lookup, problems),
                    resolve(pw, none, lookup, problems), rel, problems.isEmpty() ? null : String.join(", ", problems)));
        }
        return out;
    }

    private static String attr(String attrs, String name) {
        Matcher m = Pattern.compile("\\b" + name + "\\s*=\\s*\"([^\"]*)\"").matcher(attrs);
        return m.find() ? m.group(1).replace("&amp;", "&") : null;
    }

    /** ${key:default} 를 같은 파일의 값 → 프로젝트 properties·환경변수(lookup) 순으로 해석 */
    private static String resolve(String v, Map<String, String> local, Lookup lookup, List<String> problems) {
        if (v == null) return null;
        String cur = v;
        for (int depth = 0; depth < 5 && cur.contains("${"); depth++) {
            Matcher m = PLACEHOLDER.matcher(cur);
            StringBuilder sb = new StringBuilder();
            boolean failed = false;
            while (m.find()) {
                String key = m.group(1).strip();
                String rep = local.get(key);
                if (rep == null) rep = lookup.apply(key);
                if (rep == null) rep = m.group(2);
                if (rep == null) {
                    problems.add("${" + key + "} 값을 찾을 수 없음");
                    failed = true;
                    rep = "";
                }
                m.appendReplacement(sb, Matcher.quoteReplacement(rep));
            }
            m.appendTail(sb);
            if (failed) return null;
            cur = sb.toString();
        }
        if (cur.strip().startsWith("ENC(")) {
            problems.add("비밀번호가 암호화(ENC)되어 있음 — 환경변수 SQLGRAPH_DB_PW 로 지정");
            return null;
        }
        return cur;
    }

    // ========================================================= utils

    private static Object loadJson(String text) {
        // appsettings.json 은 // 주석과 끝 콤마를 허용한다
        String s = text.replaceAll("(?m)^\\s*//.*$", "").replaceAll("/\\*.*?\\*/", "").replaceAll(",(\\s*[}\\]])", "$1");
        try {
            return new Yaml(new SafeConstructor(new LoaderOptions())).load(s.replace("\t", "  "));
        } catch (Exception e) {
            return null;
        }
    }

    /** 프로젝트 기준 상대 경로의 폴더 (같은 이름의 연결이 여러 앱에 있어도 구분) */
    private static String dirOf(String rel) {
        int s = rel.lastIndexOf('/');
        return s < 0 ? "." : rel.substring(0, s);
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static String blankNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    private static String first(Map<String, String> kv, String... keys) {
        for (String k : keys) {
            String v = kv.get(k);
            if (v != null && !v.isBlank()) return v;
        }
        return null;
    }

    private static String find(String text, String regex) {
        Matcher m = Pattern.compile(regex).matcher(text);
        return m.find() ? m.group(1) : null;
    }

    private static int matchBrace(String s, int open) {
        int depth = 0;
        for (int k = open; k < s.length(); k++) {
            char c = s.charAt(k);
            if (c == '\'' || c == '"') {
                int e = s.indexOf(c, k + 1);
                if (e < 0) return s.length();
                k = e;
                continue;
            }
            if (c == '#') {
                int e = s.indexOf('\n', k);
                k = e < 0 ? s.length() : e;
                continue;
            }
            if (c == '{') depth++;
            else if (c == '}' && --depth == 0) return k;
        }
        return s.length();
    }
}
