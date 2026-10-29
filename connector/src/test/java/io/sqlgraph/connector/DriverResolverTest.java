package io.sqlgraph.connector;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DriverResolverTest {

    @TempDir
    Path dir;

    private static final String METADATA = """
            <metadata><versioning><versions>
              <version>8.0.33</version><version>9.1.0</version><version>9.2.0-rc1</version>
              <version>8.4.0</version><version>9.0.0</version>
            </versions></versioning></metadata>""";

    @Test
    void picksLatestReleaseVersion() {
        assertEquals("9.1.0", DriverResolver.latestVersion(METADATA, "\\d+\\.\\d+\\.\\d+"));
        String mssql = "<version>12.8.1.jre8</version><version>12.8.1.jre11</version><version>12.10.0.jre11</version><version>13.1.0.jre11-preview</version>";
        assertEquals("12.10.0.jre11", DriverResolver.latestVersion(mssql, "\\d+\\.\\d+\\.\\d+\\.jre11"));
    }

    @Test
    void downloadsVerifiesAndCaches() throws Exception {
        byte[] jar = "fake-jar".getBytes();
        String sha1 = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(jar));
        String base = "https://repo1.maven.org/maven2/com/mysql/mysql-connector-j/";
        Map<String, byte[]> files = Map.of(
                base + "maven-metadata.xml", METADATA.getBytes(),
                base + "9.1.0/mysql-connector-j-9.1.0.jar", jar,
                base + "9.1.0/mysql-connector-j-9.1.0.jar.sha1", (sha1 + "  mysql-connector-j-9.1.0.jar").getBytes());
        AtomicInteger calls = new AtomicInteger();
        DriverResolver r = new DriverResolver(dir, null, uri -> {
            calls.incrementAndGet();
            byte[] b = files.get(uri.toString());
            if (b == null) throw new IOException("404 " + uri);
            return b;
        });
        Path p = r.resolve("jdbc:mysql://localhost/x").orElseThrow();
        assertTrue(p.getFileName().toString().equals("mysql-connector-j-9.1.0.jar"));
        assertEquals("fake-jar", Files.readString(p));
        int n = calls.get();
        r.resolve("jdbc:mysql://other/y");
        assertEquals(n, calls.get(), "두 번째는 캐시 사용");
        assertTrue(r.resolve("jdbc:tibero:thin:@h:8629:t").isEmpty(), "모르는 DB");
    }

    @Test
    void rejectsChecksumMismatch() {
        String base = "https://repo1.maven.org/maven2/com/h2database/h2/";
        Map<String, byte[]> files = Map.of(
                base + "maven-metadata.xml", "<version>2.3.232</version>".getBytes(),
                base + "2.3.232/h2-2.3.232.jar", "x".getBytes(),
                base + "2.3.232/h2-2.3.232.jar.sha1", "0000".getBytes());
        DriverResolver r = new DriverResolver(dir, null, (URI uri) -> files.get(uri.toString()));
        assertThrows(IOException.class, () -> r.resolve("jdbc:h2:file:/tmp/x"));
    }
}
