package io.sqlgraph.core.model;

/**
 * 소스에서 추출된 SQL 한 건.
 *
 * @param sql      분석용 SQL (동적 태그는 평탄화, 바인드 변수는 ? 로 치환)
 * @param ownerId  쿼리 식별자. MyBatis 는 namespace.id, .sql 파일은 파일경로#순번
 * @param kind     추출기가 알고 있는 종류 (모르면 null → 분석기가 판단)
 * @param location 소스 위치
 * @param dynamic  ${...} 등 런타임에 SQL 이 바뀌는 요소가 있어 분석이 부분적일 수 있음
 */
public record ExtractedSql(String sql, String ownerId, SqlKind kind,
                           SourceLocation location, boolean dynamic) {}
