package io.sqlgraph.core.model;

import java.util.List;

/** @param kind TABLE 또는 VIEW */
public record DbTable(String schema, String name, String kind, String comment,
                      List<DbColumn> columns) {}
