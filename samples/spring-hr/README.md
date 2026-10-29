# spring-hr 샘플

`sqlgraph.yml` 없이 `application.yml` 만으로 DB 접속 정보를 자동 탐지하는 예제입니다.

- `local` 프로필의 MariaDB(`localhost:3306/hr`)를 자동 선택하고, `prod` 프로필은 운영 DB 보호를 위해 건너뜁니다.
- 테스트용 `src/test/resources/application.yml`(H2 메모리 DB)은 무시합니다.
- 스키마는 `../hr-project/ddl/mariadb_hr.sql` 을 MariaDB 에 넣어서 만듭니다.
