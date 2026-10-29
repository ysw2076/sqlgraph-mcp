package io.sqlgraph.core.model;

import java.util.List;

/**
 * 소스에서 추출된 SQL 한 건.
 *
 * @param sql      분석용 SQL (동적 태그는 평탄화, 바인드 변수는 ? 로 치환)
 * @param ownerId  쿼리 식별자. MyBatis 는 namespace.id, .sql 파일은 파일경로#순번
 * @param kind     추출기가 알고 있는 종류 (모르면 null → 분석기가 판단)
 * @param location 소스 위치
 * @param dynamic  ${...} 등 런타임에 SQL 이 바뀌는 요소가 있어 분석이 부분적일 수 있음
 * @param variants 같은 쿼리의 다른 분기 (MyBatis choose 의 두 번째 이후 when / otherwise).
 *                 분석 결과(테이블·컬럼·조인)는 sql 과 합쳐서 저장한다. 화면에는 sql 만 보인다.
 */
public record ExtractedSql(String sql, String ownerId, SqlKind kind,
                           SourceLocation location, boolean dynamic, List<String> variants) {

    public ExtractedSql {
        variants = variants == null ? List.of() : List.copyOf(variants);
    }

    public ExtractedSql(String sql, String ownerId, SqlKind kind, SourceLocation location, boolean dynamic) {
        this(sql, ownerId, kind, location, dynamic, List.of());
    }
}
