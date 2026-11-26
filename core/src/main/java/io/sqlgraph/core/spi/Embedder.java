package io.sqlgraph.core.spi;

import java.util.List;

/**
 * 문장 → 벡터 임베딩 공급자 (ONNX, HTTP 등).
 * 구현은 core 밖(connector 등)에 둔다.
 */
public interface Embedder {

    /** 임베딩 용도. e5 계열처럼 질의/문서 접두어가 다른 모델을 위해 구분한다. */
    enum Kind { QUERY, DOCUMENT }

    /** 모델 식별자. 바뀌면 캐시가 전부 무효가 된다. */
    String modelId();

    /** 벡터 차원 */
    int dim();

    /**
     * @return texts 와 같은 순서·개수의 벡터. 각 벡터 길이는 {@link #dim()}
     */
    float[][] embed(List<String> texts, Kind kind) throws Exception;
}
