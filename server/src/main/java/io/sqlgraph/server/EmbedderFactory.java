package io.sqlgraph.server;

import io.sqlgraph.connector.embed.ModelResolver;
import io.sqlgraph.connector.embed.OnnxEmbedder;
import io.sqlgraph.core.spi.Embedder;

import java.util.function.Consumer;

/** 의미 검색 설정으로 Embedder 를 연다. 테스트에서는 가짜로 바꿔 끼운다. */
public interface EmbedderFactory {

    Embedder open(Config.Semantic s, Consumer<String> log) throws Exception;

    /** 기본: 모델·런타임을 확인(필요하면 내려받기)하고 ONNX Embedder 를 연다 */
    static EmbedderFactory onnx(SqlGraphHome home) {
        return (s, log) -> {
            ModelResolver.ModelFiles files = new ModelResolver(home.dir(), ModelResolver.httpFetcher(), log)
                    .resolve(new ModelResolver.Options(s.modelPath(), s.runtimeJar()));
            return OnnxEmbedder.open(files, s.threads());
        };
    }
}
