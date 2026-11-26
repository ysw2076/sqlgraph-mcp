package io.sqlgraph.connector.embed;

/** 토큰 배치 → attention mask 평균 풀링 벡터 (정규화 전). */
public interface PooledRunner extends AutoCloseable {

    /**
     * @param inputIds      [batch][seq]
     * @param attentionMask [batch][seq], 1 = 실제 토큰, 0 = 패딩
     * @return [batch][dim]
     */
    float[][] run(long[][] inputIds, long[][] attentionMask) throws Exception;
}
