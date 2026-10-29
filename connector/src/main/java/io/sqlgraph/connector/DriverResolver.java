package io.sqlgraph.connector;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 내장되지 않은 JDBC 드라이버를 Maven Central 에서 받아 캐시 폴더에 둔다.
 * 버전은 maven-metadata.xml 에서 최신 정식 릴리스를 고르고, SHA-1 로 검증한다.
 * Maven Central 에 없는 드라이버(Tibero 등)는 설정의 driverJar 로 지정해야 한다.
 */
public final class DriverResolver {

    /** @param versionFilter 허용할 버전 형식 (예: mssql 은 .jre11 로 끝나는 것만) */
    public record Artifact(String group, String artifact, String versionFilter, String driverClass) {}

    /** JDBC URL 접두어 → Maven 좌표 */
    static final Map<String, Artifact> KNOWN = new LinkedHashMap<>();

    static {
        KNOWN.put("jdbc:mysql:", new Artifact("com.mysql", "mysql-connector-j", "\\d+\\.\\d+\\.\\d+", "com.mysql.cj.jdbc.Driver"));
        KNOWN.put("jdbc:mariadb:", new Artifact("org.mariadb.jdbc", "mariadb-java-client", "\\d+\\.\\d+\\.\\d+", "org.mariadb.jdbc.Driver"));
        KNOWN.put("jdbc:postgresql:", new Artifact("org.postgresql", "postgresql", "\\d+\\.\\d+\\.\\d+", "org.postgresql.Driver"));
        KNOWN.put("jdbc:oracle:", new Artifact("com.oracle.database.jdbc", "ojdbc11", "\\d+(\\.\\d+)+", "oracle.jdbc.OracleDriver"));
        KNOWN.put("jdbc:sqlserver:", new Artifact("com.microsoft.sqlserver", "mssql-jdbc", "\\d+\\.\\d+\\.\\d+\\.jre11", "com.microsoft.sqlserver.jdbc.SQLServerDriver"));
        KNOWN.put("jdbc:h2:", new Artifact("com.h2database", "h2", "\\d+\\.\\d+\\.\\d+", "org.h2.Driver"));
        KNOWN.put("jdbc:db2:", new Artifact("com.ibm.db2", "jcc", "\\d+(\\.\\d+)+", "com.ibm.db2.jcc.DB2Driver"));
        KNOWN.put("jdbc:sqlite:", new Artifact("org.xerial", "sqlite-jdbc", "\\d+(\\.\\d+)+", "org.sqlite.JDBC"));
    }

    private static final String REPO = "https://repo1.maven.org/maven2/";
    private static final Pattern VERSION_TAG = Pattern.compile("<version>([^<]+)</version>");

    private final Path cacheDir;
    private final Consumer<String> log;
    private final Fetcher fetcher;

    /** 네트워크 접근 (테스트에서 대체) */
    public interface Fetcher {
        byte[] get(URI uri) throws IOException, InterruptedException;
    }

    public DriverResolver(Path cacheDir, Consumer<String> log) {
        this(cacheDir, log, defaultFetcher());
    }

    DriverResolver(Path cacheDir, Consumer<String> log, Fetcher fetcher) {
        this.cacheDir = cacheDir;
        this.log = log == null ? s -> { } : log;
        this.fetcher = fetcher;
    }

    public static Optional<Artifact> artifactFor(String jdbcUrl) {
        String lower = jdbcUrl.toLowerCase(Locale.ROOT);
        for (var e : KNOWN.entrySet()) {
            if (lower.startsWith(e.getKey())) return Optional.of(e.getValue());
        }
        return Optional.empty();
    }

    /** URL 에 맞는 드라이버 jar. 캐시에 있으면 그대로, 없으면 내려받는다. 모르는 DB 면 empty */
    public Optional<Path> resolve(String jdbcUrl) throws IOException, InterruptedException {
        Optional<Artifact> a = artifactFor(jdbcUrl);
        if (a.isEmpty()) return Optional.empty();
        Artifact art = a.get();
        Path dir = cacheDir.resolve(art.group() + "_" + art.artifact());
        if (Files.isDirectory(dir)) {
            try (var s = Files.list(dir)) {
                Optional<Path> cached = s.filter(p -> p.toString().endsWith(".jar")).sorted().reduce((x, y) -> y);
                if (cached.isPresent()) return cached;
            }
        }
        String base = REPO + art.group().replace('.', '/') + "/" + art.artifact() + "/";
        String version = latestVersion(new String(fetcher.get(URI.create(base + "maven-metadata.xml"))), art.versionFilter());
        if (version == null) throw new IOException(art.artifact() + " 의 정식 버전을 찾지 못했습니다");
        String file = art.artifact() + "-" + version + ".jar";
        log.accept("JDBC 드라이버 다운로드: " + art.group() + ":" + art.artifact() + ":" + version);
        byte[] jar = fetcher.get(URI.create(base + version + "/" + file));
        String expected = new String(fetcher.get(URI.create(base + version + "/" + file + ".sha1"))).strip().split("\\s+")[0];
        String actual = sha1(jar);
        if (!actual.equalsIgnoreCase(expected)) {
            throw new IOException("드라이버 체크섬 불일치 (" + file + ")");
        }
        Files.createDirectories(dir);
        Path tmp = Files.createTempFile(dir, "dl", ".part");
        Files.write(tmp, jar);
        Path target = dir.resolve(file);
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        return Optional.of(target);
    }

    /** maven-metadata.xml 의 버전 중 필터에 맞는 가장 높은 버전 (alpha/beta/rc/SNAPSHOT 제외) */
    static String latestVersion(String metadataXml, String filter) {
        Pattern f = Pattern.compile(filter);
        List<String> ok = new ArrayList<>();
        Matcher m = VERSION_TAG.matcher(metadataXml);
        while (m.find()) {
            String v = m.group(1).strip();
            if (f.matcher(v).matches()) ok.add(v);
        }
        ok.sort(DriverResolver::compareVersions);
        return ok.isEmpty() ? null : ok.get(ok.size() - 1);
    }

    static int compareVersions(String a, String b) {
        String[] x = a.split("[.\\-]");
        String[] y = b.split("[.\\-]");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            String p = i < x.length ? x[i] : "0";
            String q = i < y.length ? y[i] : "0";
            boolean pn = p.matches("\\d+");
            boolean qn = q.matches("\\d+");
            int c;
            if (pn && qn) c = Long.compare(Long.parseLong(p), Long.parseLong(q));
            else if (pn) c = 1;
            else if (qn) c = -1;
            else c = p.compareTo(q);
            if (c != 0) return c;
        }
        return 0;
    }

    private static String sha1(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(data));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static Fetcher defaultFetcher() {
        return uri -> {
            HttpClient c = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(15))
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .build();
            HttpResponse<byte[]> r = c.send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(60)).GET().build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            if (r.statusCode() != 200) throw new IOException("HTTP " + r.statusCode() + " " + uri);
            return r.body();
        };
    }
}
