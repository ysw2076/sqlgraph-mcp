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
 * 프로젝트 하나의 인덱싱 설정.
 *
 * <p>sqlgraph.yml 은 선택 사항이다. 없으면 프로젝트 설정 파일(application.yml, .env 등)에서
 * DB 접속 정보를 자동으로 찾는다 (autoDetect). 상대 경로는 설정 파일이 있는 폴더 기준.
 *
 * <pre>
 * project: .
 * index: .sqlgraph/graph.db          # 생략 시 ~/.sqlgraph/indexes/... (프로젝트 폴더 밖)
 * autodetect: true                   # databases 가 없으면 기본 true
 * profile: local                     # 자동 탐지 시 사용할 Spring 프로필
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
 * semantic:                          # 의미 검색 (생략 시 꺼짐, 환경변수 SQLGRAPH_SEMANTIC=on|off 로도 변경)
 *   enabled: true
 *   provider: onnx                   # 지금은 onnx 만
 *   modelPath: models/e5             # 생략 시 자동 내려받기
 *   runtimeJar: libs/onnxruntime.jar # 생략 시 자동
 *   threads: 0                       # 0 = 자동
 * </pre>
 */
public record Config(Path file, Path baseDir, Path project, Path index, List<Db> databases,
                     List<String> include, List<String> exclude, long maxFileBytes,
                     boolean autoDetect, String profile, Semantic semantic) {

    /**
     * 의미 검색(임베딩) 설정.
     *
     * @param modelPath  모델 폴더 또는 .onnx (null 이면 자동 내려받기)
     * @param runtimeJar onnxruntime jar (null 이면 자동)
     * @param threads    ONNX 스레드 수 (0 = 자동)
     */
    /**
     * @param relativeThreshold 의미 검색 결과 중 1위 점수 − 이 값 이상만 쓴다 (e5 점수는 0.8~0.9 에 몰려 고정 문턱이 의미 없음)
     * @param topK              의미 검색에서 먼저 뽑는 문서 수 (테이블별로 묶기 전)
     */
    public record Semantic(boolean enabled, Path modelPath, Path runtimeJar, int threads,
                           double relativeThreshold, int topK) {
        public static final double DEFAULT_RELATIVE_THRESHOLD = 0.05;
        public static final int DEFAULT_TOP_K = 50;
        public static final Semantic OFF = new Semantic(false, null, null, 0);

        public Semantic(boolean enabled, Path modelPath, Path runtimeJar, int threads) {
            this(enabled, modelPath, runtimeJar, threads, DEFAULT_RELATIVE_THRESHOLD, DEFAULT_TOP_K);
        }

        public Semantic withEnabled(boolean e) {
            return new Semantic(e, modelPath, runtimeJar, threads, relativeThreshold, topK);
        }
    }

    public Config {
        if (semantic == null) semantic = Semantic.OFF;
    }

    /** 이전 버전 호환 (의미 검색 꺼짐) */
    public Config(Path file, Path baseDir, Path project, Path index, List<Db> databases,
                  List<String> include, List<String> exclude, long maxFileBytes,
                  boolean autoDetect, String profile) {
        this(file, baseDir, project, index, databases, include, exclude, maxFileBytes, autoDetect, profile,
                Semantic.OFF);
    }

    public record Db(String id, String type, String url, String user, String password, String passwordEnv,
                     String driverJar, String driverClass, String schema, List<String> files, String dialect,
                     Map<String, String> properties) {}

    /** Mapper XML, .sql, 프로그램 소스(코드 속 SQL·JPA), JPA 명명 규칙 확인용 Spring 설정 */
    public static final List<String> DEFAULT_INCLUDE = List.of("**/*.xml", "**/*.sql",
            "**/*.java", "**/*.kt", "**/*.kts", "**/*.scala", "**/*.groovy", "**/*.cs",
            "**/*.js", "**/*.jsx", "**/*.ts", "**/*.tsx", "**/*.mjs", "**/*.cjs",
            "**/*.go", "**/*.php", "**/*.py", "**/*.rb",
            "**/application*.yml", "**/application*.yaml", "**/application*.properties");
    static final long DEFAULT_MAX_BYTES = 5L * 1024 * 1024;

    /** @param defaultIndex index 를 지정하지 않았을 때 쓸 위치 */
    @SuppressWarnings("unchecked")
    public static Config load(Path file, Path defaultIndex) throws Exception {
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
                : defaultIndex;

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
        boolean autoDetect = root.get("autodetect") instanceof Boolean b ? b : !root.containsKey("databases");

        List<String> include = DEFAULT_INCLUDE;
        List<String> exclude = List.of();
        long maxBytes = DEFAULT_MAX_BYTES;
        if (root.get("sources") instanceof Map<?, ?> sm) {
            Map<String, Object> s = (Map<String, Object>) sm;
            if (s.get("include") != null) include = strList(s.get("include"));
            if (s.get("exclude") != null) exclude = strList(s.get("exclude"));
            if (s.get("maxFileKb") instanceof Number num) maxBytes = num.longValue() * 1024;
        }
        return new Config(abs, base, project, index, dbs, include, exclude, maxBytes, autoDetect,
                str(root.get("profile"), null), semantic(root.get("semantic"), base));
    }

    @SuppressWarnings("unchecked")
    private static Semantic semantic(Object o, Path base) {
        if (!(o instanceof Map<?, ?> m)) return Semantic.OFF;
        Map<String, Object> s = (Map<String, Object>) m;
        Object en = s.get("enabled");
        boolean enabled = en instanceof Boolean b ? b : en != null && Boolean.parseBoolean(String.valueOf(en).strip());
        String provider = str(s.get("provider"), null);
        if (provider != null && !provider.isBlank() && !provider.strip().equalsIgnoreCase("onnx")) {
            throw new IllegalArgumentException("semantic.provider '" + provider
                    + "' 는 지원하지 않습니다. 지금은 onnx 만 쓸 수 있습니다 (생략하면 onnx)");
        }
        Path model = s.get("modelPath") == null ? null : base.resolve(str(s.get("modelPath"), null)).normalize();
        Path jar = s.get("runtimeJar") == null ? null : base.resolve(str(s.get("runtimeJar"), null)).normalize();
        int threads = 0;
        Object t = s.get("threads");
        if (t instanceof Number n) {
            threads = n.intValue();
        } else if (t != null) {
            try {
                threads = Integer.parseInt(String.valueOf(t).strip());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("semantic.threads 는 정수여야 합니다: " + t);
            }
        }
        if (threads < 0) throw new IllegalArgumentException("semantic.threads 는 0 이상이어야 합니다: " + threads);
        double rel = Semantic.DEFAULT_RELATIVE_THRESHOLD;
        Object r = s.get("relativeThreshold");
        if (r != null) {
            try {
                rel = r instanceof Number n ? n.doubleValue() : Double.parseDouble(String.valueOf(r).strip());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("semantic.relativeThreshold 는 숫자여야 합니다: " + r);
            }
            if (rel < 0 || rel > 2) {
                throw new IllegalArgumentException("semantic.relativeThreshold 는 0~2 사이여야 합니다: " + rel);
            }
        }
        int topK = Semantic.DEFAULT_TOP_K;
        Object k = s.get("topK");
        if (k != null) {
            try {
                topK = k instanceof Number n ? n.intValue() : Integer.parseInt(String.valueOf(k).strip());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("semantic.topK 는 정수여야 합니다: " + k);
            }
            if (topK < 1) throw new IllegalArgumentException("semantic.topK 는 1 이상이어야 합니다: " + topK);
        }
        return new Semantic(enabled, model, jar, threads, rel, topK);
    }

    /**
     * 환경변수 SQLGRAPH_SEMANTIC 값 적용: on|true|1 → 켬, off|false|0 → 끔, 그 외(null·빈 값 포함) → 그대로.
     */
    public static Config applySemanticEnv(Config c, String value) {
        if (c == null || value == null) return c;
        String v = value.strip().toLowerCase(java.util.Locale.ROOT);
        return switch (v) {
            case "on", "true", "1" -> c.withSemantic(c.semantic().withEnabled(true));
            case "off", "false", "0" -> c.withSemantic(c.semantic().withEnabled(false));
            default -> c;
        };
    }

    /** 같은 설정에 의미 검색 설정만 바꾼 것 */
    public Config withSemantic(Semantic s) {
        return new Config(file, baseDir, project, index, databases, include, exclude, maxFileBytes, autoDetect,
                profile, s == null ? Semantic.OFF : s);
    }

    /** 설정 파일 없이 프로젝트 폴더만으로 (DB 는 자동 탐지) */
    public static Config ofProject(Path project, Path index, String profile) {
        Path p = project.toAbsolutePath().normalize();
        return new Config(null, p, p, index, List.of(), DEFAULT_INCLUDE, List.of(), DEFAULT_MAX_BYTES, true, profile);
    }

    /** 같은 설정에 프로필만 바꾼 것 */
    public Config withProfile(String p) {
        return new Config(file, baseDir, project, index, databases, include, exclude, maxFileBytes, autoDetect,
                p == null ? profile : p, semantic);
    }

    /** DB 없이 소스만 인덱싱 */
    public Config withoutDatabases() {
        return new Config(file, baseDir, project, index, List.of(), include, exclude, maxFileBytes, false, profile,
                semantic);
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
