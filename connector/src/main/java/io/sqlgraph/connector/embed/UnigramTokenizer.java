package io.sqlgraph.connector.embed;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 순수 Java SentencePiece Unigram 토크나이저 (XLM-R / e5 계열 tokenizer.json).
 * NFKC → 연속 공백 정리 → Metaspace(▁) → 코드포인트 단위 Viterbi → unk 묶기.
 */
public final class UnigramTokenizer {

    private static final String META = "▁";
    private static final Pattern MULTI_SPACE = Pattern.compile(" {2,}");
    private static final double UNK_PENALTY = 10.0;

    private final Map<String, Integer> ids;
    private final double[] scores;
    private final int unkId;
    private final int bosId;
    private final int eosId;
    private final int padId;
    private final int maxPieceLen;   // 코드포인트 수
    private final double unkScore;

    private UnigramTokenizer(List<String> pieces, double[] scores, int unkId) {
        this.scores = scores;
        this.unkId = unkId;
        this.ids = new HashMap<>(pieces.size() * 2);
        int maxLen = 1;
        double min = Double.POSITIVE_INFINITY;
        for (int i = 0; i < pieces.size(); i++) {
            String p = pieces.get(i);
            ids.putIfAbsent(p, i);
            maxLen = Math.max(maxLen, p.codePointCount(0, p.length()));
            min = Math.min(min, scores[i]);
        }
        this.maxPieceLen = maxLen;
        this.unkScore = (pieces.isEmpty() ? 0.0 : min) - UNK_PENALTY;
        this.bosId = ids.getOrDefault("<s>", 0);
        this.eosId = ids.getOrDefault("</s>", 2);
        this.padId = ids.getOrDefault("<pad>", 1);
    }

    public static UnigramTokenizer load(Path tokenizerJson) throws IOException {
        JsonNode root;
        try (InputStream in = Files.newInputStream(tokenizerJson)) {
            root = new ObjectMapper().readTree(in);
        }
        JsonNode model = root == null ? null : root.get("model");
        String type = model == null ? null : model.path("type").asText(null);
        if (!"Unigram".equals(type)) {
            throw new IllegalArgumentException("Unigram 토크나이저가 아닙니다 (model.type=" + type + "): " + tokenizerJson);
        }
        JsonNode vocab = model.get("vocab");
        if (vocab == null || !vocab.isArray()) {
            throw new IllegalArgumentException("model.vocab 이 없습니다: " + tokenizerJson);
        }
        List<String> pieces = new ArrayList<>(vocab.size());
        double[] scores = new double[vocab.size()];
        for (int i = 0; i < vocab.size(); i++) {
            JsonNode e = vocab.get(i);
            pieces.add(e.get(0).asText());
            scores[i] = e.get(1).asDouble();
        }
        int unk = model.path("unk_id").asInt(0);
        return new UnigramTokenizer(pieces, scores, unk);
    }

    public int padId() {
        return padId;
    }

    public int vocabSize() {
        return scores.length;
    }

    /** @return [bos] + 토큰 + [eos], 길이는 maxTokens 이하 */
    public int[] encode(String text, int maxTokens) {
        String s = Normalizer.normalize(text == null ? "" : text, Normalizer.Form.NFKC);
        s = MULTI_SPACE.matcher(s).replaceAll(" ").strip();

        List<Integer> out = new ArrayList<>();
        out.add(bosId);
        for (String word : s.split(" ")) {
            if (word.isEmpty()) continue;
            viterbi(META + word, out);
        }
        out.add(eosId);

        int n = out.size();
        if (maxTokens > 0 && n > maxTokens) {
            int[] r = new int[maxTokens];
            for (int i = 0; i < maxTokens - 1; i++) r[i] = out.get(i);
            r[maxTokens - 1] = eosId;
            return r;
        }
        int[] r = new int[n];
        for (int i = 0; i < n; i++) r[i] = out.get(i);
        return r;
    }

    private void viterbi(String word, List<Integer> out) {
        int[] cps = word.codePoints().toArray();
        int n = cps.length;
        int[] off = new int[n + 1];   // 코드포인트 i 의 char 시작 위치
        for (int i = 0; i < n; i++) off[i + 1] = off[i] + Character.charCount(cps[i]);

        double[] best = new double[n + 1];
        int[] from = new int[n + 1];
        int[] tok = new int[n + 1];
        java.util.Arrays.fill(best, Double.NEGATIVE_INFINITY);
        best[0] = 0;
        for (int start = 0; start < n; start++) {
            if (best[start] == Double.NEGATIVE_INFINITY) continue;
            boolean single = false;
            int lim = Math.min(n, start + maxPieceLen);
            for (int end = start + 1; end <= lim; end++) {
                Integer id = ids.get(word.substring(off[start], off[end]));
                if (id == null) continue;
                if (end == start + 1) single = true;
                double sc = best[start] + scores[id];
                if (sc > best[end]) {
                    best[end] = sc;
                    from[end] = start;
                    tok[end] = id;
                }
            }
            if (!single) {
                double sc = best[start] + unkScore;
                if (sc > best[start + 1]) {
                    best[start + 1] = sc;
                    from[start + 1] = start;
                    tok[start + 1] = unkId;
                }
            }
        }

        List<Integer> rev = new ArrayList<>();
        for (int e = n; e > 0; e = from[e]) rev.add(tok[e]);
        boolean prevUnk = false;
        for (int i = rev.size() - 1; i >= 0; i--) {
            int id = rev.get(i);
            boolean unk = id == unkId;
            if (unk && prevUnk) continue;
            out.add(id);
            prevUnk = unk;
        }
    }
}
