package io.sqlgraph.core.model;

import java.util.List;

/**
 * DB 하나의 메타데이터 스냅샷.
 *
 * @param dbId     설정의 db id
 * @param product  DB 제품명 (MariaDB, Oracle ...)
 * @param warnings 수집 중 건너뛴 항목 등
 */
public record SchemaSnapshot(String dbId, String product, List<DbTable> tables,
                             List<ForeignKey> foreignKeys, List<String> warnings) {}
