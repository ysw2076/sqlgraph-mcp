package io.sqlgraph.server;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;

/**
 * sqlgraph 의 사용자 데이터 폴더 (기본 ~/.sqlgraph, 환경변수 SQLGRAPH_HOME 으로 변경).
 * <pre>
 * ~/.sqlgraph/
 *   projects.json            인덱싱한 프로젝트 목록
 *   indexes/&lt;이름&gt;-&lt;해시&gt;/graph.db  프로젝트별 인덱스
 *   drivers/                 자동으로 받은 JDBC 드라이버
 * </pre>
 * 인덱스를 프로젝트 폴더 밖에 두어서 회사 저장소에 DB 구조가 커밋되는 일을 막는다.
 */
public record SqlGraphHome(Path dir) {

    public static SqlGraphHome fromEnv() {
        String env = System.getenv("SQLGRAPH_HOME");
        Path d = env != null && !env.isBlank()
                ? Path.of(env)
                : Path.of(System.getProperty("user.home"), ".sqlgraph");
        return new SqlGraphHome(d.toAbsolutePath().normalize());
    }

    public Path registryFile() {
        return dir.resolve("projects.json");
    }

    public Path drivers() {
        return dir.resolve("drivers");
    }

    public Path indexFor(Path project) {
        Path p = project.toAbsolutePath().normalize();
        String name = p.getFileName() == null ? "root" : p.getFileName().toString();
        name = name.replaceAll("[^\\w.-]", "_");
        return dir.resolve("indexes").resolve(name + "-" + hash(key(p))).resolve("graph.db");
    }

    /** Windows 는 대소문자를 구분하지 않으므로 비교용 키를 소문자로 */
    static String key(Path p) {
        String s = p.toAbsolutePath().normalize().toString();
        return isWindows() ? s.toLowerCase(Locale.ROOT) : s;
    }

    static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    private static String hash(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-1").digest(s.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(d).substring(0, 8);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
