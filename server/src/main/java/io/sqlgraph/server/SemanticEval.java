package io.sqlgraph.server;

import io.sqlgraph.core.store.GraphStore;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 의미 검색 평가: 평가셋(yml)의 질의를 keyword / semantic / auto 세 모드로 돌려 정답의 순위를 비교한다.
 * 계산(Recall@k, MRR)은 순수 함수로 분리해 테스트한다.
 */
public final class SemanticEval {

    /** @param expect 정답 테이블 이름 (하나라도 맞으면 정답), kind 는 없으면 null */
    public record Query(String q, List<String> expect, String kind) {}

    /** @param recall Recall@k (정답이 k 위 안에 든 질의 비율), mrr 평균 역순위 (못 찾으면 0) */
    public record Metrics(double recall, double mrr, int queries) {}

    public enum Mode { KEYWORD, SEMANTIC, AUTO }

    /** 한 질의의 모드별 정답 첫 순위 (1부터, 없으면 null) */
    public record Row(Query query, Map<Mode, Integer> ranks, long autoMillis) {}

    static final int CANDIDATES = 50;

    private SemanticEval() {}

    @SuppressWarnings("unchecked")
    public static List<Query> load(Path yml) throws IOException {
        Object root;
        try (Reader r = Files.newBufferedReader(yml, StandardCharsets.UTF_8)) {
            root = new Yaml().load(r);
        }
        if (!(root instanceof Map<?, ?> m) || !(m.get("queries") instanceof List<?> list)) {
            throw new IllegalArgumentException("평가 파일에 queries: 목록이 없습니다: " + yml);
        }
        List<Query> out = new ArrayList<>();
        for (Object o : list) {
            if (!(o instanceof Map<?, ?> e)) continue;
            Object q = e.get("q");
            Object ex = e.get("expect");
            if (q == null || ex == null) throw new IllegalArgumentException("q 와 expect 가 필요합니다: " + e);
            List<String> expect = new ArrayList<>();
            if (ex instanceof List<?> l) {
                for (Object x : l) expect.add(String.valueOf(x));
            } else {
                expect.add(String.valueOf(ex));
            }
            Object kind = e.get("kind");
            out.add(new Query(String.valueOf(q), List.copyOf(expect), kind == null ? null : String.valueOf(kind)));
        }
        return out;
    }

    /** 순위 목록(테이블 이름)에서 정답의 첫 순위 (1부터, 대소문자 무시, 없으면 null) */
    public static Integer firstRank(List<String> ranked, List<String> expect) {
        for (int i = 0; i < ranked.size(); i++) {
            String n = ranked.get(i);
            for (String e : expect) {
                if (n != null && n.equalsIgnoreCase(e)) return i + 1;
            }
        }
        return null;
    }

    /** 질의별 정답 첫 순위(없으면 null) → Recall@k, MRR */
    public static Metrics score(List<Integer> firstRanks, int k) {
        if (firstRanks.isEmpty()) return new Metrics(0, 0, 0);
        int hit = 0;
        double rr = 0;
        for (Integer r : firstRanks) {
            if (r == null) continue;
            if (r <= k) hit++;
            rr += 1.0 / r;
        }
        return new Metrics(hit / (double) firstRanks.size(), rr / firstRanks.size(), firstRanks.size());
    }

    /** 각 질의를 세 모드로 돌린다. 호출 전에 svc.semanticReady() 를 확인한다 */
    public static List<Row> run(SqlGraphService svc, List<Query> queries) throws Exception {
        GraphStore st = svc.store();
        List<Row> rows = new ArrayList<>();
        for (Query q : queries) {
            Map<Mode, Integer> ranks = new LinkedHashMap<>();
            List<String> kw = st.searchTables(q.q(), false, CANDIDATES).stream().map(GraphStore.TableHit::name).toList();
            ranks.put(Mode.KEYWORD, firstRank(kw, q.expect()));
            ranks.put(Mode.SEMANTIC, firstRank(names(svc.hybridSearch(q.q(), false, CANDIDATES, true)), q.expect()));
            long t0 = System.nanoTime();
            List<String> auto = names(svc.hybridSearch(q.q(), false, CANDIDATES, false));
            long ms = (System.nanoTime() - t0) / 1_000_000;
            ranks.put(Mode.AUTO, firstRank(auto, q.expect()));
            rows.add(new Row(q, ranks, ms));
        }
        return rows;
    }

    private static List<String> names(List<SqlGraphService.HybridHit> hits) {
        return hits.stream().map(h -> h.table().name()).toList();
    }

    /** 결과 표 + 모드별·분류별 Recall@k, MRR + auto 지연 */
    public static String report(List<Row> rows, int k) {
        StringBuilder sb = new StringBuilder("## 의미 검색 평가 (질의 ").append(rows.size()).append("개, k=").append(k)
                .append(")\n\n| 질의 | 분류 | 정답 | keyword | semantic | auto |\n|---|---|---|---|---|---|\n");
        for (Row r : rows) {
            sb.append("| ").append(r.query().q()).append(" | ").append(r.query().kind() == null ? "" : r.query().kind())
                    .append(" | ").append(String.join(", ", r.query().expect()));
            for (Mode m : Mode.values()) sb.append(" | ").append(rankText(r.ranks().get(m)));
            sb.append(" |\n");
        }
        sb.append("\n| 모드 | Recall@").append(k).append(" | MRR |\n|---|---|---|\n");
        for (Mode m : Mode.values()) {
            Metrics x = score(rows.stream().map(r -> r.ranks().get(m)).toList(), k);
            sb.append("| ").append(m.name().toLowerCase(Locale.ROOT)).append(" | ").append(fmt(x.recall()))
                    .append(" | ").append(fmt(x.mrr())).append(" |\n");
        }
        Map<String, List<Row>> byKind = new LinkedHashMap<>();
        for (Row r : rows) {
            if (r.query().kind() != null) byKind.computeIfAbsent(r.query().kind(), x -> new ArrayList<>()).add(r);
        }
        if (!byKind.isEmpty()) {
            sb.append("\n| 분류 | 질의 | keyword | semantic | auto |\n|---|---|---|---|---|\n");
            for (var e : byKind.entrySet()) {
                sb.append("| ").append(e.getKey()).append(" | ").append(e.getValue().size());
                for (Mode m : Mode.values()) {
                    sb.append(" | ").append(fmt(score(e.getValue().stream().map(r -> r.ranks().get(m)).toList(), k).recall()));
                }
                sb.append(" |\n");
            }
            sb.append("(분류별 값은 Recall@").append(k).append(")\n");
        }
        long[] ms = rows.stream().mapToLong(Row::autoMillis).sorted().toArray();
        if (ms.length > 0) {
            sb.append("\nauto 검색 지연: p50 ").append(pct(ms, 50)).append("ms, p95 ").append(pct(ms, 95))
                    .append("ms, 최대 ").append(ms[ms.length - 1]).append("ms (첫 질의는 모델 열기 포함)\n");
        }
        return sb.toString();
    }

    private static String rankText(Integer r) {
        return r == null ? "-" : String.valueOf(r);
    }

    private static String fmt(double d) {
        return String.format(Locale.ROOT, "%.3f", d);
    }

    static long pct(long[] sorted, int p) {
        int i = (int) Math.ceil(p / 100.0 * sorted.length) - 1;
        return sorted[Math.max(0, Math.min(sorted.length - 1, i))];
    }
}
