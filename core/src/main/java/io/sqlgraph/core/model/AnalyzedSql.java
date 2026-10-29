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

    /** 같은 쿼리의 다른 분기 분석 결과를 합친다 (테이블·컬럼·조인 합집합). 종류와 오류는 this 기준. */
    public AnalyzedSql merge(AnalyzedSql o) {
        if (o == null) return this;
        boolean ok = parsed || o.parsed;
        return new AnalyzedSql(kind, ok, ok ? null : error,
                union(tables, o.tables), union(columns, o.columns), selectsAll || o.selectsAll,
                union(joins, o.joins));
    }

    private static <T> List<T> union(List<T> a, List<T> b) {
        java.util.LinkedHashSet<T> s = new java.util.LinkedHashSet<>(a);
        s.addAll(b);
        return List.copyOf(s);
    }
}
