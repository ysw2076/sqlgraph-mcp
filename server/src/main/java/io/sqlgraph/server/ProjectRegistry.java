package io.sqlgraph.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 인덱싱한 프로젝트 목록 (~/.sqlgraph/projects.json).
 * 여러 Claude 세션이 동시에 쓸 수 있으므로 매번 다시 읽고, 쓸 때는 임시 파일 → 이동으로 교체한다.
 */
public final class ProjectRegistry {

    /** @param config 명시적으로 지정한 sqlgraph.yml (없으면 null) */
    public record Entry(String name, Path path, Path config, String indexedAt) {}

    private final Path file;
    private final ObjectMapper om = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    public ProjectRegistry(Path file) {
        this.file = file;
    }

    public synchronized List<Entry> list() {
        List<Entry> out = new ArrayList<>();
        if (!Files.isRegularFile(file)) return out;
        try {
            for (var n : om.readTree(file.toFile()).path("projects")) {
                String cfg = n.path("config").asText(null);
                out.add(new Entry(n.path("name").asText(), Path.of(n.path("path").asText()),
                        cfg == null || cfg.isEmpty() ? null : Path.of(cfg), n.path("indexedAt").asText(null)));
            }
        } catch (Exception e) {
            // 깨진 파일은 무시 (다음 저장 때 새로 씀)
        }
        return out;
    }

    public Optional<Entry> find(Path path) {
        String k = SqlGraphHome.key(path);
        return list().stream().filter(e -> SqlGraphHome.key(e.path()).equals(k)).findFirst();
    }

    public Optional<Entry> findByName(String name) {
        return list().stream().filter(e -> e.name().equalsIgnoreCase(name)).findFirst();
    }

    /** 추가 또는 갱신. 이름이 겹치면 뒤에 -2, -3 … 을 붙인다 */
    public synchronized Entry upsert(Path path, Path config, String indexedAt) {
        List<Entry> all = list();
        String k = SqlGraphHome.key(path);
        Entry existing = all.stream().filter(e -> SqlGraphHome.key(e.path()).equals(k)).findFirst().orElse(null);
        String name;
        if (existing != null) {
            name = existing.name();
            all.remove(existing);
            if (config == null) config = existing.config();
            if (indexedAt == null) indexedAt = existing.indexedAt();
        } else {
            String baseName = path.getFileName() == null ? "project" : path.getFileName().toString();
            name = baseName;
            int i = 2;
            while (true) {
                String n = name;
                if (all.stream().noneMatch(e -> e.name().equalsIgnoreCase(n))) break;
                name = baseName + "-" + i++;
            }
        }
        Entry e = new Entry(name, path.toAbsolutePath().normalize(), config, indexedAt);
        all.add(e);
        save(all);
        return e;
    }

    private void save(List<Entry> all) {
        try {
            Files.createDirectories(file.getParent());
            ObjectNode root = om.createObjectNode();
            ArrayNode arr = root.putArray("projects");
            for (Entry e : all) {
                ObjectNode n = arr.addObject();
                n.put("name", e.name());
                n.put("path", e.path().toString());
                if (e.config() != null) n.put("config", e.config().toString());
                if (e.indexedAt() != null) n.put("indexedAt", e.indexedAt());
            }
            Path tmp = Files.createTempFile(file.getParent(), "projects", ".tmp");
            om.writeValue(tmp.toFile(), root);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception ex) {
            throw new IllegalStateException("프로젝트 목록 저장 실패: " + file + " (" + ex.getMessage() + ")", ex);
        }
    }
}
