package io.sqlgraph.core.model;

import java.util.List;

/**
 * SQL 분석 결과.
 *
 * @param parsed     JSqlParser 파싱 성공 여부. 실패 시 정규식으로 테이블만 추출
 * @param error      파싱 실패 사유
 * @param selectsAll SELECT * 사용 여부 (컬럼 영향도 분석 시 "가능성 있음"으로 분류)
 * @param joins      조인 조건에서 찾은 컬럼 간 관계
 */
public record AnalyzedSql(SqlKind kind, boolean parsed, String error,
                          List<TableRef> tables, List<ColumnRef> columns,
                          boolean selectsAll, List<JoinRef> joins) {

    public AnalyzedSql withColumnsAndJoins(List<ColumnRef> cols, List<JoinRef> js) {
        return new AnalyzedSql(kind, parsed, error, tables, cols, selectsAll, js);
    }
}
