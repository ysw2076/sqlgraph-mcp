package io.sqlgraph.core.spi;

import io.sqlgraph.core.model.SchemaSnapshot;

/** DB 메타데이터 공급자. 새 DB 나 입력 형식은 이 인터페이스를 구현해서 추가한다. */
public interface SchemaSource {
    /** 설정의 db id */
    String id();

    SchemaSnapshot load() throws Exception;
}
