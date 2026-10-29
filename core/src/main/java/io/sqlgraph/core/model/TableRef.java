package io.sqlgraph.core.model;

/** SQL 이 참조하는 테이블. schema 는 SQL 에 적혀 있을 때만 채워진다. */
public record TableRef(String schema, String name, Access access) {}
