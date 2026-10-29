package io.sqlgraph.connector.detect;

import io.sqlgraph.core.TextFiles;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.IOException;
import java.io.StringReader;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 프로젝트 안의 설정 파일에서 DB 접속 정보를 찾는다. 파일은 읽기만 한다.
 *
 * <ul>
 *   <li>Spring Boot: application*.yml / application*.properties (프로필, ${ENV:default}, 다중 datasource)</li>
 *   <li>.env: DATABASE_URL (postgres://, mysql:// …) 또는 DB_HOST/DB_PORT/DB_DATABASE/DB_USERNAME/DB_PASSWORD</li>
 * </ul>
 */
public final class DbConfigDetector {

    /** 같은 DB 의 후보가 여러 프로필에 있을 때 고르는 순서 (앞일수록 우선) */
    public static final List<String> PROFILE_PRIORITY = List.of("local", "dev", "develop", "development", "");

    private static final Set<String> SKIP_DIRS = Set.of(
            ".git", ".svn", ".idea", ".vscode", ".gradle", ".sqlgraph", "node_modules", "build", "target",
            "out", "bin", "dist", "test", "tests", "__pycache__", ".venv", "venv", "vendor");
    private static final Pattern SPRING_FILE = Pattern.compile("application(-[\\w.]+)?\\.(ya?ml|properties)");
    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([^}:]+)(?::([^}]*))?}");
    private static final int MAX_DEPTH = 8;

    private final Function<String, String> env;

    public DbConfigDetector() {
        this(System::getenv);
    }

    /** 테스트용: 환경변수 조회 함수를 주입 */
    public DbConfigDetector(Function<String, String> env) {
        this.env = env;
    }

    /** 결과: 모든 후보와, 그룹별로 고른 후보 */
    public record Result(List<DetectedDb> all, List<DetectedDb> chosen, List<String> notes) {}

    /**
     * @param profile 사용자가 지정한 프로필 (null 이면 PROFILE_PRIORITY 순)
     */
    public Result detect(Path projectRoot, String profile) throws IOException {
        Path root = projectRoot.toAbsolutePath().normalize();
        List<DetectedDb> all = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        List<Path> springFiles = new ArrayList<>();
        List<Path> envFiles = new ArrayList<>();

        Files.walkFileTree(root, Set.of(), MAX_DEPTH, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                if (!dir.equals(root) && SKIP_DIRS.contains(dir.getFileName().toString().toLowerCase(Locale.ROOT))) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                String name = file.getFileName().toString();
                if (SPRING_FILE.matcher(name).matches()) springFiles.add(file);
                else if (name.equals(".env") || name.equals(".env.local") || name.equals(".env.development")) envFiles.add(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) {
                return FileVisitResult.CONTINUE;
            }
        });
        springFiles.sort(Comparator.naturalOrder());
        envFiles.sort(Comparator.naturalOrder());

        for (Path f : springFiles) {
            try {
                all.addAll(fromSpring(root, f));
            } catch (Exception e) {
                notes.add(rel(root, f) + " 읽기 실패: " + e.getMessage());
            }
        }
        for (Path f : envFiles) {
            try {
                all.addAll(fromEnvFile(root, f));
            } catch (Exception e) {
                notes.add(rel(root, f) + " 읽기 실패: " + e.getMessage());
            }
        }
        List<DetectedDb> chosen = choose(all, profile, notes);
        return new Result(all, chosen, notes);
    }

    // ================================================================ Spring

    List<DetectedDb> fromSpring(Path root, Path file) throws IOException {
        String name = file.getFileName().toString();
        Matcher nm = SPRING_FILE.matcher(name);
        nm.matches();
        String fileProfile = nm.group(1) == null ? "" : nm.group(1).substring(1);
        String text = TextFiles.read(file);

        // 문서별 (yml 의 --- 로 나뉜 문서, properties 는 하나) 평탄화된 키-값
        List<Map.Entry<String, Map<String, String>>> docs = new ArrayList<>();
        if (name.endsWith(".properties")) {
            Properties p = new Properties();
            p.load(new StringReader(text));
            Map<String, String> flat = new LinkedHashMap<>();
            for (String k : p.stringPropertyNames()) flat.put(k, p.getProperty(k));
            docs.add(Map.entry(fileProfile, flat));
        } else {
            Yaml yaml = new Yaml(new SafeConstructor(new LoaderOptions()));
            for (Object doc : yaml.loadAll(text)) {
                Map<String, String> flat = new LinkedHashMap<>();
                flatten("", doc, flat);
                String docProfile = fileProfile;
                String onProfile = first(flat, "spring.config.activate.on-profile", "spring.profiles");
                if (onProfile != null && !onProfile.isBlank()) docProfile = onProfile.split("[,\\s|&!()]+")[0];
                docs.add(Map.entry(docProfile, flat));
            }
        }

        // 프로필 없는 문서의 값은 다른 문서의 ${} 해석에도 쓴다
        Map<String, String> base = new LinkedHashMap<>();
        for (var d : docs) if (d.getKey().isEmpty()) base.putAll(d.getValue());

        List<DetectedDb> out = new ArrayList<>();
        String rel = rel(root, file);
        for (var d : docs) {
            Map<String, String> props = new LinkedHashMap<>(base);
            props.putAll(d.getValue());
            for (String prefix : datasourcePrefixes(d.getValue())) {
                String rawUrl = first(d.getValue(), prefix + ".url", prefix + ".jdbc-url", prefix + ".jdbcUrl");
                String rawUser = first(props, prefix + ".username", prefix + ".user");
                String rawPw = first(props, prefix + ".password");
                List<String> problems = new ArrayList<>();
                String url = resolve(rawUrl, props, problems);
                String user = resolve(rawUser, props, problems);
                String pw = resolve(rawPw, props, problems);
                if (pw != null && pw.strip().startsWith("ENC(")) {
                    problems.add("비밀번호가 암호화(jasypt ENC)되어 있음 — 환경변수 SQLGRAPH_DB_PW 로 지정");
                    pw = null;
                }
                if (url == null || !url.startsWith("jdbc:")) continue;
                if (url.startsWith("jdbc:h2:mem") || url.startsWith("jdbc:hsqldb:mem") || url.startsWith("jdbc:derby:memory")) {
                    continue;   // 메모리 DB 는 인덱싱 의미가 없음
                }
                url = url.replace("jdbc:log4jdbc:", "jdbc:").replace("jdbc:p6spy:", "jdbc:");
                String group = "spring:" + prefix;
                out.add(new DetectedDb(group, d.getKey().isEmpty() ? null : d.getKey(), url, user, pw, rel,
                        problems.isEmpty() ? null : String.join(", ", problems)));
            }
        }
        return out;
    }

    /** url/jdbc-url 키를 가진 datasource 접두어 (spring.datasource, spring.datasource.hikari, app.datasource.hr …) */
    private static Set<String> datasourcePrefixes(Map<String, String> flat) {
        Set<String> out = new LinkedHashSet<>();
        for (var e : flat.entrySet()) {
            String k = e.getKey();
            String v = e.getValue();
            String lk = k.toLowerCase(Locale.ROOT);
            if (!(lk.endsWith(".url") || lk.endsWith(".jdbc-url") || lk.endsWith(".jdbcurl"))) continue;
            if (v == null || !(v.contains("jdbc:") || v.contains("${"))) continue;
            if (!lk.contains("datasource") && !v.startsWith("jdbc:")) continue;
            out.add(k.substring(0, k.lastIndexOf('.')));
        }
        // spring.datasource 와 spring.datasource.hikari 가 같은 DB 를 가리키면 하나만
        if (out.contains("spring.datasource") && out.contains("spring.datasource.hikari")) {
            out.remove("spring.datasource.hikari");
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static void flatten(String prefix, Object node, Map<String, String> out) {
        if (node instanceof Map<?, ?> m) {
            for (var e : ((Map<Object, Object>) m).entrySet()) {
                String k = prefix.isEmpty() ? String.valueOf(e.getKey()) : prefix + "." + e.getKey();
                flatten(k, e.getValue(), out);
            }
        } else if (node instanceof List<?> l) {
            for (int i = 0; i < l.size(); i++) flatten(prefix + "[" + i + "]", l.get(i), out);
        } else if (node != null && !prefix.isEmpty()) {
            out.put(prefix, String.valueOf(node));
        }
    }

    /** ${ENV:default} / ${other.property} 해석. 못 하면 problems 에 기록하고 null */
    String resolve(String value, Map<String, String> props, List<String> problems) {
        if (value == null) return null;
        String cur = value;
        for (int depth = 0; depth < 5 && cur.contains("${"); depth++) {
            Matcher m = PLACEHOLDER.matcher(cur);
            StringBuilder sb = new StringBuilder();
            boolean failed = false;
            while (m.find()) {
                String key = m.group(1).strip();
                String def = m.group(2);
                String rep = env.apply(key);
                if (rep == null) rep = env.apply(key.toUpperCase(Locale.ROOT).replace('.', '_').replace('-', '_'));
                if (rep == null) rep = props.get(key);
                if (rep == null) rep = def;
                if (rep == null) {
                    problems.add("${" + key + "} 값을 찾을 수 없음 (환경변수 " + key + " 설정 필요)");
                    failed = true;
                    rep = "";
                }
                m.appendReplacement(sb, Matcher.quoteReplacement(rep));
            }
            m.appendTail(sb);
            if (failed) return null;
            cur = sb.toString();
        }
        return cur;
    }

    // =================================================================== .env

    List<DetectedDb> fromEnvFile(Path root, Path file) throws IOException {
        Map<String, String> vars = new LinkedHashMap<>();
        for (String line : TextFiles.read(file).split("\\R")) {
            String l = line.strip();
            if (l.isEmpty() || l.startsWith("#")) continue;
            if (l.startsWith("export ")) l = l.substring(7).strip();
            int eq = l.indexOf('=');
            if (eq <= 0) continue;
            String k = l.substring(0, eq).strip();
            String v = l.substring(eq + 1).strip();
            if ((v.startsWith("\"") && v.endsWith("\"") && v.length() >= 2) || (v.startsWith("'") && v.endsWith("'") && v.length() >= 2)) {
                v = v.substring(1, v.length() - 1);
            } else {
                int hash = v.indexOf(" #");
                if (hash >= 0) v = v.substring(0, hash).strip();
            }
            vars.put(k, v);
        }
        String rel = rel(root, file);
        List<DetectedDb> out = new ArrayList<>();
        String dbUrl = vars.get("DATABASE_URL");
        if (dbUrl != null && !dbUrl.isBlank()) {
            DetectedDb d = fromUrl("env:DATABASE_URL", dbUrl, rel);
            if (d != null) out.add(d);
        }
        String host = vars.get("DB_HOST");
        String type = firstNonNull(vars.get("DB_CONNECTION"), vars.get("DB_TYPE"), vars.get("DB_DIALECT"), vars.get("DB_ENGINE"));
        if (host != null && type != null) {
            String scheme = scheme(type);
            String name = firstNonNull(vars.get("DB_DATABASE"), vars.get("DB_NAME"));
            String port = vars.get("DB_PORT");
            String user = firstNonNull(vars.get("DB_USERNAME"), vars.get("DB_USER"));
            String pw = firstNonNull(vars.get("DB_PASSWORD"), vars.get("DB_PASS"));
            if (scheme != null) {
                String url = jdbcUrl(scheme, host, port, name);
                out.add(new DetectedDb("env:DB_*", null, url, user, pw, rel, null));
            }
        }
        return out;
    }

    /** postgres://user:pw@host:5432/db 형식 → JDBC */
    static DetectedDb fromUrl(String group, String raw, String source) {
        if (raw.startsWith("jdbc:")) return new DetectedDb(group, null, raw, null, null, source, null);
        try {
            URI u = URI.create(raw);
            String scheme = scheme(u.getScheme());
            if (scheme == null) return null;
            String user = null;
            String pw = null;
            if (u.getRawUserInfo() != null) {
                String[] ui = u.getRawUserInfo().split(":", 2);
                user = URLDecoder.decode(ui[0], StandardCharsets.UTF_8);
                if (ui.length > 1) pw = URLDecoder.decode(ui[1], StandardCharsets.UTF_8);
            }
            String db = u.getPath() == null ? null : u.getPath().replaceFirst("^/", "");
            String port = u.getPort() > 0 ? String.valueOf(u.getPort()) : null;
            return new DetectedDb(group, null, jdbcUrl(scheme, u.getHost(), port, db), user, pw, source, null);
        } catch (Exception e) {
            return new DetectedDb(group, null, null, null, null, source, "DATABASE_URL 형식을 해석할 수 없음");
        }
    }

    /** 다양한 표기 → JDBC 서브프로토콜 */
    static String scheme(String s) {
        if (s == null) return null;
        String t = s.toLowerCase(Locale.ROOT);
        if (t.contains("postgres") || t.equals("pgsql")) return "postgresql";
        if (t.contains("mariadb")) return "mariadb";
        if (t.contains("mysql")) return "mysql";
        if (t.contains("sqlserver") || t.contains("mssql") || t.equals("sqlsrv")) return "sqlserver";
        if (t.contains("oracle")) return "oracle";
        return null;
    }

    static String jdbcUrl(String scheme, String host, String port, String db) {
        String h = host == null ? "localhost" : host;
        String d = db == null ? "" : db;
        return switch (scheme) {
            case "sqlserver" -> "jdbc:sqlserver://" + h + (port != null ? ":" + port : "")
                    + (d.isEmpty() ? "" : ";databaseName=" + d) + ";encrypt=false";
            case "oracle" -> "jdbc:oracle:thin:@//" + h + ":" + (port != null ? port : "1521") + "/" + d;
            default -> "jdbc:" + scheme + "://" + h + (port != null ? ":" + port : "") + "/" + d;
        };
    }

    // ================================================================ choose

    /** 그룹(같은 DB)마다 프로필 우선순위로 하나씩 고른다 */
    static List<DetectedDb> choose(List<DetectedDb> all, String profile, List<String> notes) {
        Map<String, List<DetectedDb>> groups = new LinkedHashMap<>();
        for (DetectedDb d : all) groups.computeIfAbsent(d.group(), k -> new ArrayList<>()).add(d);
        List<DetectedDb> out = new ArrayList<>();
        for (var e : groups.entrySet()) {
            List<DetectedDb> cands = e.getValue();
            DetectedDb pick = null;
            if (profile != null) {
                pick = cands.stream().filter(c -> profile.equalsIgnoreCase(nz(c.profile()))).findFirst().orElse(null);
                if (pick == null) {
                    notes.add(e.getKey() + ": 프로필 '" + profile + "' 설정이 없어 기본 우선순위로 선택");
                }
            }
            if (pick == null) {
                for (String p : PROFILE_PRIORITY) {
                    pick = cands.stream().filter(c -> c.usable() && p.equalsIgnoreCase(nz(c.profile()))).findFirst().orElse(null);
                    if (pick != null) break;
                }
            }
            if (pick == null) {
                // 우선순위에 없는 프로필(prod 등)만 있으면 자동으로 고르지 않는다
                List<String> profs = cands.stream().map(c -> nz(c.profile())).distinct().toList();
                notes.add(e.getKey() + ": 자동 선택할 프로필이 없습니다 (있는 프로필: " + String.join(", ", profs)
                        + "). 운영 DB 접속을 피하려고 건너뜁니다. 필요하면 profile 을 지정하세요");
                continue;
            }
            out.add(pick);
        }
        // URL 이 같은 것은 하나로
        Map<String, DetectedDb> byUrl = new LinkedHashMap<>();
        for (DetectedDb d : out) byUrl.putIfAbsent(d.url() == null ? d.group() : d.url(), d);
        return new ArrayList<>(byUrl.values());
    }

    // ================================================================ utils

    private static String first(Map<String, String> m, String... keys) {
        for (String k : keys) {
            String v = m.get(k);
            if (v != null) return v;
        }
        return null;
    }

    @SafeVarargs
    private static <T> T firstNonNull(T... vs) {
        for (T v : vs) if (v != null) return v;
        return null;
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static String rel(Path root, Path f) {
        return root.relativize(f).toString().replace('\\', '/');
    }
}
