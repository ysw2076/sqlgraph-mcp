package io.sqlgraph.server;

import io.sqlgraph.core.Names;
import io.sqlgraph.core.store.GraphStore;
import io.sqlgraph.core.store.GraphStore.Relation;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 테이블 관계 그래프: DB 의 FK + 쿼리 조인 조건에서 찾은 관계.
 * describe_table 의 관계 목록과 find_join_path 의 경로 탐색에 쓴다.
 */
final class Relations {

    /** 컬럼 쌍 하나 (a.ca = b.cb [cond]) */
    record Pair(String a, String ca, String b, String cb, String cond, int count, boolean fk) {

        Pair flip() {
            return new Pair(b, cb, a, ca, cond, count, fk);
        }

        String key() {
            String l = a + "." + ca;
            String r = b + "." + cb;
            return (l.compareTo(r) <= 0 ? l + "=" + r : r + "=" + l) + "|" + cond;
        }
    }

    /**
     * 경로의 한 단계: from 에서 to 로 가는 조인 컬럼들.
     *
     * @param alternatives 같은 대상 컬럼으로 가는 다른 조인 컬럼 (예: BEF_DEPT_CD / AFT_DEPT_CD → DEPT_CD).
     *                     복합키가 아니라 의미가 다른 별개 관계이므로 AND 로 묶지 않고 따로 알려 준다
     */
    record Step(String from, String to, List<Pair> pairs, List<Pair> alternatives) {
        int score() {
            int s = 0;
            for (Pair p : pairs) s = Math.max(s, (p.fk ? 100 : 0) + p.count);
            return s;
        }
    }

    private final Map<String, Pair> pairs = new LinkedHashMap<>();
    private final Set<String> pk;

    private Relations(Set<String> pk) {
        this.pk = pk;
    }

    static Relations load(GraphStore st) throws Exception {
        Relations r = new Relations(st.primaryKeyColumns());
        for (Relation rel : st.allRelations()) {
            r.add(new Pair(rel.t1(), rel.c1(), rel.t2(), rel.c2(), nz(rel.cond()), rel.count(), false));
        }
        for (String[] fk : st.allForeignKeys()) {
            if (fk[2] == null || fk[3] == null) continue;
            r.add(new Pair(fk[0], fk[1], fk[2], fk[3], "", 0, true));
        }
        return r;
    }

    /** 같은 컬럼 쌍이면 FK 표시와 쿼리 건수를 합친다 */
    private void add(Pair p) {
        String k = p.key();
        Pair old = pairs.get(k);
        if (old == null) {
            pairs.put(k, p);
        } else {
            pairs.put(k, new Pair(old.a, old.ca, old.b, old.cb, old.cond, old.count + p.count, old.fk || p.fk));
        }
    }

    boolean isPk(String table, String col) {
        return pk.contains(Names.key(table) + "." + Names.key(col));
    }

    /** 이 테이블이 들어간 관계 (FK 우선, 근거 많은 순) */
    List<Pair> forTable(String table) {
        String t = Names.key(Names.lastPart(table));
        List<Pair> out = new ArrayList<>();
        for (Pair p : pairs.values()) {
            if (p.a.equals(t)) out.add(p);
            else if (p.b.equals(t)) out.add(p.flip());
        }
        out.sort(Comparator.comparing((Pair p) -> !p.fk).thenComparing(p -> -p.count));
        return out;
    }

    /** 관계 한 줄: 참조 방향(비 PK → PK)을 화살표로 */
    String describe(Pair p) {
        boolean aPk = isPk(p.a, p.ca);
        boolean bPk = isPk(p.b, p.cb);
        String arrow = bPk && !aPk ? " → " : aPk && !bPk ? " ← " : " = ";
        StringBuilder sb = new StringBuilder(p.a + "." + p.ca + arrow + p.b + "." + p.cb);
        if (!p.cond.isEmpty()) sb.append("  [").append(p.cond).append("]");
        List<String> src = new ArrayList<>();
        if (p.fk) src.add("FK");
        if (p.count > 0) src.add("쿼리 " + p.count + "건");
        sb.append("  (").append(String.join(" · ", src)).append(")");
        return sb.toString();
    }

    // ======================================================== path finding

    /** 인접: 테이블 → (상대 테이블 → 컬럼 쌍들) */
    private Map<String, Map<String, List<Pair>>> adjacency() {
        Map<String, Map<String, List<Pair>>> adj = new HashMap<>();
        for (Pair p : pairs.values()) {
            if (p.a.equals(p.b)) continue;   // 셀프 조인은 경로에 쓰지 않음
            adj.computeIfAbsent(p.a, k -> new HashMap<>()).computeIfAbsent(p.b, k -> new ArrayList<>()).add(p);
            adj.computeIfAbsent(p.b, k -> new HashMap<>()).computeIfAbsent(p.a, k -> new ArrayList<>()).add(p.flip());
        }
        return adj;
    }

    /**
     * 두 테이블 사이에 쓸 컬럼 쌍.
     * 복합키처럼 서로 다른 컬럼끼리 이어지는 쌍은 함께 쓰고(AND), 한쪽 컬럼이 겹치는 쌍은 의미가 다른
     * 별개 관계로 보고 가장 강한 것 하나만 쓴다 (나머지는 alternatives).
     */
    private static Step chooseColumns(String from, String to, List<Pair> cands) {
        List<Pair> sorted = new ArrayList<>(cands);
        sorted.sort(Comparator.comparingInt((Pair p) -> -((p.fk ? 100 : 0) + p.count)));
        int max = sorted.isEmpty() ? 0 : sorted.get(0).count;
        List<Pair> chosen = new ArrayList<>();
        List<Pair> alts = new ArrayList<>();
        Set<String> usedFrom = new HashSet<>();
        Set<String> usedTo = new HashSet<>();
        for (Pair p : sorted) {
            boolean strong = p.fk || p.count >= Math.max(1, (max + 1) / 2);
            boolean overlaps = usedFrom.contains(p.ca) || usedTo.contains(p.cb);
            if (strong && !overlaps) {
                chosen.add(p);
                usedFrom.add(p.ca);
                usedTo.add(p.cb);
            } else if (overlaps && !(usedFrom.contains(p.ca) && usedTo.contains(p.cb))) {
                alts.add(p);
            }
        }
        return new Step(from, to, chosen, alts);
    }

    /** 홉 수가 가장 적은 경로들 중 근거가 강한 순으로 최대 limit 개 */
    List<List<Step>> paths(String from, String to, int maxHops, int limit) {
        String s = Names.key(Names.lastPart(from));
        String t = Names.key(Names.lastPart(to));
        Map<String, Map<String, List<Pair>>> adj = adjacency();
        if (!adj.containsKey(s) || !adj.containsKey(t)) return List.of();

        // BFS 로 s 로부터의 거리
        Map<String, Integer> dist = new HashMap<>();
        Deque<String> q = new ArrayDeque<>();
        dist.put(s, 0);
        q.add(s);
        while (!q.isEmpty()) {
            String u = q.poll();
            int d = dist.get(u);
            if (d >= maxHops) continue;
            for (String v : adj.getOrDefault(u, Map.of()).keySet()) {
                if (!dist.containsKey(v)) {
                    dist.put(v, d + 1);
                    q.add(v);
                }
            }
        }
        if (!dist.containsKey(t)) return List.of();

        // 최단 경로 나열 (거리가 1씩 줄어드는 방향으로 역추적)
        List<List<String>> routes = new ArrayList<>();
        backtrack(t, s, dist, adj, new ArrayDeque<>(List.of(t)), routes, 200);
        List<List<Step>> out = new ArrayList<>();
        for (List<String> r : routes) {
            List<Step> steps = new ArrayList<>();
            for (int i = 0; i + 1 < r.size(); i++) {
                String a = r.get(i), b = r.get(i + 1);
                steps.add(chooseColumns(a, b, adj.get(a).get(b)));
            }
            out.add(steps);
        }
        out.sort(Comparator.comparingInt((List<Step> p) -> -p.stream().mapToInt(Step::score).min().orElse(0))
                .thenComparingInt(p -> -p.stream().mapToInt(Step::score).sum()));
        return out.size() > limit ? out.subList(0, limit) : out;
    }

    private static void backtrack(String cur, String s, Map<String, Integer> dist,
                                  Map<String, Map<String, List<Pair>>> adj, Deque<String> acc,
                                  List<List<String>> out, int cap) {
        if (out.size() >= cap) return;
        if (cur.equals(s)) {
            out.add(new ArrayList<>(acc));
            return;
        }
        int d = dist.get(cur);
        for (String prev : adj.getOrDefault(cur, Map.of()).keySet()) {
            Integer pd = dist.get(prev);
            if (pd != null && pd == d - 1) {
                acc.addFirst(prev);
                backtrack(prev, s, dist, adj, acc, out, cap);
                acc.removeFirst();
            }
        }
    }

    /** 경로를 FROM … JOIN … ON … 형태로 */
    static String toSql(List<Step> path) {
        Map<String, String> alias = new HashMap<>();
        Set<String> used = new HashSet<>();
        StringBuilder sb = new StringBuilder();
        String first = path.get(0).from();
        sb.append("FROM ").append(first).append(' ').append(alias(first, alias, used)).append('\n');
        for (Step st : path) {
            String fa = alias(st.from(), alias, used);
            String ta = alias(st.to(), alias, used);
            List<String> on = new ArrayList<>();
            for (Pair p : st.pairs()) {
                on.add(ta + "." + p.cb + " = " + fa + "." + p.ca);
                if (!p.cond.isEmpty()) {
                    for (String c : p.cond.split(" AND ")) {
                        String tbl = c.substring(0, c.indexOf('.'));
                        String rest = c.substring(c.indexOf('.') + 1).replace("=", " = ");
                        String al = tbl.equals(p.b) ? ta : tbl.equals(p.a) ? fa : tbl;
                        on.add(al + "." + rest);
                    }
                }
            }
            List<String> src = new ArrayList<>();
            boolean fk = st.pairs().stream().anyMatch(Pair::fk);
            int cnt = st.pairs().stream().mapToInt(Pair::count).max().orElse(0);
            if (fk) src.add("FK");
            if (cnt > 0) src.add("쿼리 " + cnt + "건");
            sb.append("JOIN ").append(st.to()).append(' ').append(ta).append(" ON ")
                    .append(String.join(" AND ", on)).append("   -- ").append(String.join(" · ", src)).append('\n');
            if (!st.alternatives().isEmpty()) {
                sb.append("   -- 다른 조인 컬럼(별개 관계): ");
                sb.append(String.join(", ", st.alternatives().stream()
                        .map(p -> ta + "." + p.cb + " = " + fa + "." + p.ca + (p.count > 0 ? " (쿼리 " + p.count + "건)" : "")).toList()));
                sb.append('\n');
            }
        }
        return sb.toString();
    }

    private static String alias(String table, Map<String, String> alias, Set<String> used) {
        return alias.computeIfAbsent(table, t -> {
            StringBuilder a = new StringBuilder();
            for (String part : t.toLowerCase().split("_")) if (!part.isEmpty()) a.append(part.charAt(0));
            String base = a.isEmpty() ? "t" : a.toString();
            String cand = base;
            int i = 2;
            while (!used.add(cand)) cand = base + i++;
            return cand;
        });
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
