# sqlgraph-mcp

DB 스키마(테이블·컬럼·코멘트·PK·FK)와 소스 속 SQL(MyBatis/iBatis Mapper XML, `.sql`)을 미리 인덱싱해 두고,
Claude Code 같은 AI 코딩 도구가 **grep 없이 한 번에** 조회하게 해 주는 로컬 MCP 서버입니다.

> "HR_EMP.DEPT_CD 컬럼 바꾸면 영향받는 쿼리랑 Mapper 다 알려줘"
> → `impact_analysis` 한 번으로 쓰기/읽기, 확실/가능성 구분된 목록이 나옵니다.

## 동작 방식

```
[DB 커넥터]                [SQL 추출기]                    [그래프 (SQLite)]
JDBC 메타데이터             MyBatis 3 Mapper XML            테이블 · 컬럼 · 코멘트 · FK
DDL 파일 (접속 불가 환경)    iBatis 2 SqlMap XML             쿼리 ─ 사용 테이블 (읽기/쓰기)
                           .sql 파일                       쿼리 ─ 사용 컬럼 (읽기/쓰기)
                                                           쿼리 ─ Mapper id · 파일:줄
```

1. **DB 메타데이터 수집**: JDBC `DatabaseMetaData` 로 테이블·컬럼·코멘트·PK·FK 만 읽습니다 (데이터는 읽지 않음).
   접속이 안 되면 DDL 파일(mysqldump, Oracle export)을 파싱합니다.
2. **SQL 추출**: Mapper XML 의 동적 태그(`if`, `where`, `set`, `foreach`, `include` …)를 평탄화해 분석 가능한 SQL 로 만듭니다.
3. **SQL 분석**: JSqlParser 로 파싱해 테이블·컬럼과 읽기/쓰기 여부, 별칭을 해석합니다.
   `WHERE EMP_NO = ?` 처럼 테이블이 지정되지 않은 컬럼은 DB 메타데이터로 어느 테이블 컬럼인지 확정합니다.
   파싱에 실패한 쿼리도 정규식으로 테이블은 뽑아 둡니다.
4. **저장**: `<project>/.sqlgraph/graph.db` (SQLite) 에 저장. MCP 도구는 이 파일만 조회하므로 보통 수십 ms 안에 응답합니다.

## 요구 사항

- JDK 25 (빌드·실행)
- 빌드: 포함된 Gradle Wrapper (9.1.0)

## 빌드

```bash
./gradlew build
# → server/build/libs/sqlgraph-mcp-0.1.0-all.jar   (실행 jar 하나)
# → server/build/install/sqlgraph/bin/sqlgraph     (./gradlew :server:installDist 시 실행 스크립트)
```

폐쇄망에서는 `libs/` 에 jar 를 넣고 `./gradlew build -Poffline` (자세한 목록은 `libs/README.md`).

## 설정 (`sqlgraph.yml`)

프로젝트 루트에 둡니다. `java -jar sqlgraph-mcp-0.1.0-all.jar init` 으로 예시 파일을 만들 수 있습니다.
상대 경로는 설정 파일 위치 기준입니다.

```yaml
project: .                      # 소스 루트
# index: .sqlgraph/graph.db     # 인덱스 위치 (기본값)

databases:
  - id: hr                      # MariaDB / MySQL
    type: jdbc
    url: jdbc:mariadb://localhost:3306/hr
    user: reader
    passwordEnv: HR_DB_PW       # 비밀번호는 환경변수로 (password: 직접 지정도 가능)

  - id: pay                     # Oracle — 스키마(소유자) 지정
    type: jdbc
    url: jdbc:oracle:thin:@//dbhost:1521/ORCL
    user: reader
    passwordEnv: PAY_DB_PW
    schema: PAY

  - id: legacy                  # 내장되지 않은 드라이버 (Tibero, Altibase, Cubrid …)
    type: jdbc
    url: jdbc:tibero:thin:@dbhost:8629:tibero
    user: reader
    passwordEnv: LEGACY_DB_PW
    driverJar: ./drivers/tibero7-jdbc.jar     # DBeaver 가 받아 둔 jar 를 지정해도 됩니다
    # driverClass: com.tmax.tibero.jdbc.TbDriver

  - id: ext                     # DB 접속이 안 되는 환경: DDL 파일로
    type: ddl
    files: ["ddl/*.sql"]
    dialect: oracle

sources:
  include: ["**/*.xml", "**/*.sql"]
  exclude: ["**/test/**"]
  # maxFileKb: 5120
```

- 내장 드라이버: MariaDB(MySQL 겸용), PostgreSQL, Oracle(ojdbc11), SQL Server.
  `jdbc:mysql:` URL 은 MySQL 드라이버가 없으면 MariaDB 드라이버로 자동 접속합니다.
- 읽기 전용 계정을 권장합니다. 필요한 권한은 메타데이터 조회 수준입니다 (MariaDB 예: `GRANT SELECT, SHOW VIEW ON hr.*`).
- 기본 제외 폴더: `.git`, `build`, `target`, `out`, `bin`, `dist`, `node_modules`, `.idea`, `.gradle`, `.sqlgraph` 등.
- UTF-8 이 아닌 파일은 MS949(EUC-KR) 로 다시 읽습니다.

## Claude Code 에 등록

```bash
claude mcp add sqlgraph -- java -jar /path/to/sqlgraph-mcp-0.1.0-all.jar serve
```

또는 프로젝트 루트의 `.mcp.json`:

```json
{
  "mcpServers": {
    "sqlgraph": {
      "type": "stdio",
      "command": "java",
      "args": ["-jar", "/path/to/sqlgraph-mcp-0.1.0-all.jar", "serve"],
      "env": { "HR_DB_PW": "..." }
    }
  }
}
```

Windows 는 경로를 `"C:\\tools\\sqlgraph-mcp-0.1.0-all.jar"` 처럼 적습니다.
Claude Code 가 프로젝트 폴더에서 서버를 띄우므로 그 폴더의 `sqlgraph.yml` 을 자동으로 읽습니다
(다른 위치면 `serve -c /path/sqlgraph.yml`). 인덱스가 없으면 시작할 때 자동으로 만듭니다.

확인: Claude Code 에서 `/mcp` → `sqlgraph` 가 connected 이고 도구 6개.

`.gitignore` 에 `.sqlgraph/` 를 추가하세요.

## MCP 도구

| 도구 | 용도 | 예시 질문 |
|---|---|---|
| `search_table` | 테이블명·한글 코멘트(옵션: 컬럼까지)로 검색 | "발령 관련 테이블 뭐 있어?" |
| `describe_table` | 컬럼·PK·NULL·코멘트·FK + 이 테이블을 쓰는 쿼리 | "HR_EMP 구조랑 쓰는 곳 보여줘" |
| `impact_analysis` | 테이블/컬럼 변경 영향도 (쓰기/읽기, 확실/가능성) | "EMAIL 컬럼 길이 바꾸면 영향 범위?" |
| `find_query` | Mapper id / SQL 키워드로 실제 SQL 조회 | "EmpMapper.selectEmpList 쿼리 보여줘" |
| `index_status` | 인덱스 상태, 파싱 실패 샘플, DB 에 없는 테이블 | "인덱스 상태 확인해줘" |
| `index_project` | 다시 인덱싱 | "Mapper 바꿨으니 다시 인덱싱해" |

`impact_analysis` 의 "사용 가능성 있음"은 `SELECT *`, 테이블을 특정할 수 없는 동명 컬럼, `${}` 동적 SQL, 파싱 실패 쿼리입니다.

## CLI

```bash
java -jar sqlgraph-mcp-0.1.0-all.jar index                       # 인덱싱만
java -jar sqlgraph-mcp-0.1.0-all.jar call describe_table '{"table":"HR_EMP"}'
java -jar sqlgraph-mcp-0.1.0-all.jar call impact_analysis '{"table":"HR_EMP","column":"DEPT_CD"}'
java -jar sqlgraph-mcp-0.1.0-all.jar serve -c ./sqlgraph.yml      # MCP 서버
java -jar sqlgraph-mcp-0.1.0-all.jar init                        # sqlgraph.yml 예시 생성
```

설정 파일 없이 `-p <프로젝트폴더>` 만 주면 DB 없이 소스만 인덱싱합니다.

## 분석 규칙과 한계

- **동적 SQL**: 모든 `<if>` 가 참이라고 보고 펼칩니다. `<choose>` 는 **첫 번째 `<when>`** 만 사용합니다.
  다른 분기에만 있는 테이블/컬럼은 잡히지 않습니다.
- **`<include>`**: 같은 파일의 `<sql>` 조각만 펼칩니다. 다른 Mapper 파일의 조각은 "동적"으로 표시됩니다.
- **`${...}`**: 런타임 값이라 분석에서 제외하고 "동적"으로 표시합니다 (`FROM ${table}` 의 테이블은 알 수 없음).
- **프로시저 호출**(`{call ...}`, `CALL`)은 쿼리로 등록하지만 프로시저 내부 테이블은 분석하지 않습니다.
- **변경 반영**: 자동 감지하지 않습니다. Mapper 나 스키마가 바뀌면 `index_project`(또는 `index`)를 다시 실행하세요.
- **테이블 매칭**은 이름 기준(대소문자 무시)입니다. 여러 DB 에 같은 이름의 테이블이 있으면 함께 표시됩니다.

## 확장

새 DB 입력 형식이나 언어/프레임워크는 인터페이스 하나씩 구현해서 추가합니다.

- `io.sqlgraph.core.spi.SchemaSource` — DB 메타데이터 공급자 (예: 테이블정의서 엑셀)
- `io.sqlgraph.core.spi.SqlExtractor` — 소스에서 SQL 추출 (예: JPA `@Query`, 코드 내 SQL 문자열)

구현체를 `SqlGraphService` 에 등록하면 저장·분석·도구는 그대로 동작합니다.

## 모듈 구조

```
core/       모델, SPI, SqlAnalyzer(JSqlParser), GraphStore(SQLite), Indexer
connector/  JdbcSchemaSource, DdlSchemaSource
extractor/  MyBatisXmlExtractor, SqlFileExtractor
server/     MCP 서버(stdio JSON-RPC), 도구, CLI, 설정
samples/    hr-project — MariaDB/Oracle DDL, MyBatis/iBatis Mapper, .sql 예제 (테스트에 사용)
```

## 개발 메모

- **MCP 프로토콜**은 공식 SDK 없이 직접 구현했습니다 (stdio, JSON-RPC 2.0: `initialize`, `tools/list`, `tools/call`, `ping`).
  의존성이 적어 GraalVM native-image 로 단일 실행 파일을 만들기 쉽습니다.
- **JSqlParser 4.9** 를 사용합니다. 5.x 는 Visitor 인터페이스 시그니처가 바뀌어서 (`visit(X, context)`)
  `SqlAnalyzer.Collector` 를 함께 수정해야 합니다.
- 테스트: `./gradlew test` — 분석기, Mapper 평탄화, DDL 파싱, 샘플 프로젝트 전체 인덱싱, MCP 프로토콜 왕복.

## 다음 단계 (2단계 후보)

- JPA `@Query` / 네이티브 쿼리, 코드 내 SQL 문자열 추출 (tree-sitter, Java·Python·PHP·C# 등)
- 다른 파일의 `<sql>` 조각 `<include>` 해석, `<choose>` 전 분기 분석
- 파일 변경 감지 후 증분 인덱싱
- 공통코드 테이블 해석 (코드그룹 → 코드값 의미)
- 테이블정의서(엑셀) 입력, GraalVM 단일 실행 파일 배포
