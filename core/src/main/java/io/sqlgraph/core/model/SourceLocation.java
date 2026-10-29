package io.sqlgraph.core.model;

/** 소스 위치. file 은 프로젝트 루트 기준 상대경로. */
public record SourceLocation(String file, int line) {
    @Override public String toString() { return line > 0 ? file + ":" + line : file; }
}
