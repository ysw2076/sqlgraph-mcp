package io.sqlgraph.core.model;

public record ForeignKey(String table, String column, String refTable, String refColumn) {}
