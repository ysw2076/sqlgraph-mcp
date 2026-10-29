package io.sqlgraph.core.model;

/**
 * SQL 이 참조하는 컬럼.
 *
 * @param table 실제 테이블명 (별칭 해석 후). 테이블을 특정할 수 없으면 null
 */
public record ColumnRef(String table, String column, Access access) {
    public ColumnRef withTable(String t) { return new ColumnRef(t, column, access); }
}
