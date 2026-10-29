package io.sqlgraph.core.model;

public record DbColumn(String name, String type, Integer size, boolean nullable,
                       boolean primaryKey, int ordinal, String comment) {}
