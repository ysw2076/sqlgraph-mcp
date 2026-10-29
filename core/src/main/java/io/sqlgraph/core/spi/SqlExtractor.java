package io.sqlgraph.core.spi;

import io.sqlgraph.core.model.ExtractedSql;

import java.util.List;

/** 소스 파일에서 SQL 을 뽑아내는 추출기. 새 언어/프레임워크는 이 인터페이스를 구현해서 추가한다. */
public interface SqlExtractor {
    String name();

    /** 파일명/경로만 보고 1차 판단 */
    boolean supports(String relativePath);

    /**
     * @param relativePath 프로젝트 루트 기준 경로 ('/' 구분자)
     * @param content      파일 내용
     */
    List<ExtractedSql> extract(String relativePath, String content);
}
