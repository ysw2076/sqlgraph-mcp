package io.sqlgraph.connector.embed;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class UnigramTokenizerTest {

    @TempDir
    Path dir;

    static Path resource() throws Exception {
        return Path.of(UnigramTokenizerTest.class.getResource("/embed/tokenizer.json").toURI());
    }

    static UnigramTokenizer tok() throws Exception {
        return UnigramTokenizer.load(resource());
    }

    @Test
    void basicInfo() throws Exception {
        UnigramTokenizer t = tok();
        assertEquals(14, t.vocabSize());
        assertEquals(1, t.padId());
    }

    @Test
    void picksHighestScoreNotLongestPiece() throws Exception {
        // 탐욕: ▁ab(-10) + c(-5) = -15, Viterbi: ▁a(-1) + bc(-1) = -2
        assertArrayEquals(new int[]{0, 6, 7, 2}, tok().encode("abc", 512));
    }

    @Test
    void unknownCharsFuseIntoSingleUnk() throws Exception {
        assertArrayEquals(new int[]{0, 6, 3, 9, 2}, tok().encode("a한글b", 512));
    }

    @Test
    void fullWidthNormalizedByNfkc() throws Exception {
        assertArrayEquals(new int[]{0, 11, 2}, tok().encode("ＡＢＣ", 512));
    }

    @Test
    void repeatedSpacesCollapsed() throws Exception {
        assertArrayEquals(new int[]{0, 6, 7, 13, 2}, tok().encode("  abc    x  ", 512));
    }

    @Test
    void truncationKeepsEos() throws Exception {
        UnigramTokenizer t = tok();
        assertArrayEquals(new int[]{0, 13, 13, 13, 13, 13, 2}, t.encode("x x x x x", 512));
        int[] r = t.encode("x x x x x", 4);
        assertEquals(4, r.length);
        assertArrayEquals(new int[]{0, 13, 13, 2}, r);
        assertEquals(2, r[r.length - 1]);
    }

    @Test
    void emojiOutsideVocabIsOneUnk() throws Exception {
        UnigramTokenizer t = tok();
        assertArrayEquals(new int[]{0, 13, 3, 2}, t.encode("x😀", 512));
        assertArrayEquals(new int[]{0, 4, 3, 12, 2}, t.encode("😀x", 512));
        assertArrayEquals(new int[]{0, 4, 3, 2}, t.encode("😀😁", 512));
    }

    @Test
    void emptyTextIsBosEos() throws Exception {
        assertArrayEquals(new int[]{0, 2}, tok().encode("   ", 512));
    }

    @Test
    void nonUnigramRejected() throws Exception {
        Path f = dir.resolve("bpe.json");
        Files.writeString(f, "{\"model\":{\"type\":\"BPE\",\"vocab\":{}}}");
        assertThrows(IllegalArgumentException.class, () -> UnigramTokenizer.load(f));
    }
}
