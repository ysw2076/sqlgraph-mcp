package io.sqlgraph.core.spi;

import io.sqlgraph.core.model.ExtractedSql;

import java.util.List;

/** 소스 파일에서 SQL 을 뽑아내는 추출기. 새 언어/프레임워크는 이 인터페이스를 구현해서 추가한다. */
public interface SqlExtractor {
    String name();

    /** 파일명/경로만 보고 1차 판단 */
    boolean supports(String relativePath);

    /**
     * 다른 파일을 참조해야 하는 추출기(MyBatis 의 다른 Mapper 의 sql 조각 include 등)는 true.
     * true 면 Indexer 가 extract 전에 모든 대상 파일을 {@link #prepare} 로 한 번 먼저 보여준다.
     */
    default boolean needsPrepare() {
        return false;
    }

    /** 1차 패스: 파일 간 참조 정보를 모은다. extract 는 모든 prepare 가 끝난 뒤 호출된다. */
    default void prepare(String relativePath, String content) {
    }

    /**
     * @param relativePath 프로젝트 루트 기준 경로 ('/' 구분자)
     * @param content      파일 내용
     */
    List<ExtractedSql> extract(String relativePath, String content);
}
