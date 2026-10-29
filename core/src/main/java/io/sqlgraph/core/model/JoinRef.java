package io.sqlgraph.core.model;

import java.util.List;

/**
 * 쿼리의 조인 조건에서 찾은 컬럼 간 관계 (a.X = b.Y). FK 가 없어도 관계를 알 수 있다.
 *
 * @param leftLiterals  왼쪽 테이블에 같은 쿼리에서 걸린 "컬럼 = 상수" 조건 (예: GRP_CD='POS').
 *                      공통코드처럼 PK 일부를 상수로 고정하는 패턴을 잡기 위한 후보.
 *                      실제로 쓸지는 DB 메타데이터(PK)를 보고 Indexer 가 정한다.
 * @param rightLiterals 오른쪽 테이블의 같은 후보
 */
public record JoinRef(String leftTable, String leftColumn, String rightTable, String rightColumn,
                      List<String> leftLiterals, List<String> rightLiterals) {

    public JoinRef withTables(String l, String r) {
        return new JoinRef(l, leftColumn, r, rightColumn, leftLiterals, rightLiterals);
    }
}
