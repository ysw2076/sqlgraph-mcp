package io.sqlgraph.core;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** 텍스트 파일 읽기. UTF-8 로 읽다가 깨지면 MS949(EUC-KR 확장)로 다시 읽는다. */
public final class TextFiles {
    private static final Charset MS949 = Charset.forName("x-windows-949");

    private TextFiles() {}

    public static String read(Path file) throws IOException {
        byte[] bytes = Files.readAllBytes(file);
        int off = (bytes.length >= 3 && (bytes[0] & 0xFF) == 0xEF && (bytes[1] & 0xFF) == 0xBB
                && (bytes[2] & 0xFF) == 0xBF) ? 3 : 0;   // UTF-8 BOM
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes, off, bytes.length - off)).toString();
        } catch (CharacterCodingException e) {
            return new String(bytes, MS949);
        }
    }
}
