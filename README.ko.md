# sqlgraph-mcp

[English](README.md) | **한국어**

DB 스키마(테이블·컬럼·코멘트·PK·FK)와 소스 속 SQL(MyBatis/iBatis Mapper XML, `.sql`, JPA `@Query`·JPQL,
Java·Kotlin·Python·JS/TS·Go·C#·PHP·Ruby 코드 속 SQL 문자열)을 인덱싱해 두고,
Claude Code 같은 AI 코딩 도구가 **grep 없이 한 번에** 조회하게 해 주는 로컬 MCP 서버입니다.

```
나:     C:\work\hr-system DB 인덱싱해줘
Claude: (index_project) application.yml 에서 MariaDB 를 찾아 테이블 214개, 쿼리 1,840건 인덱싱했습니다.
나:     HR_EMP.DEPT_CD 바꾸면 영향받는 곳은?
Claude: (impact_analysis) 쓰기 3건, 읽기 12건 + SELECT * 등 가능성 4건 … (Mapper id · 파일:줄)
```

## 설치

Java 를 따로 설치할 필요가 없습니다. 배포 파일에 경량 Java 런타임이 들어 있습니다.

**Windows (PowerShell)**
```powershell
irm https://raw.githubusercontent.com/ysw2076/sqlgraph-mcp/main/install.ps1 | iex
```

**macOS / Linux**
```bash
curl -fsSL https://raw.githubusercontent.com/ysw2076/sqlgraph-mcp/main/install.sh | sh
```

설치 스크립트가 하는 일:
1. OS 에 맞는 배포 파일을 GitHub Releases 에서 받아 `~/.sqlgraph/app` 에 설치
2. `sqlgraph` 명령을 PATH 에 추가
3. `sqlgraph install` 로 **Claude Code 에 MCP 서버 자동 등록** (모든 프로젝트에서 사용, `--scope user`)

Claude Code 를 새로 열고 `/mcp` 에서 `sqlgraph` 가 connected 인지 확인하면 끝입니다.

> 인터넷이 막힌 환경에서는 Releases 페이지에서 OS 에 맞는 zip 을 받아 `SQLGRAPH_ZIP` 으로 지정하세요.
> ```powershell
> $env:SQLGRAPH_ZIP = "$HOME\Downloads\sqlgraph-mcp-windows-x64.zip"; .\install.ps1
> ```
> ```bash
> SQLGRAPH_ZIP=~/Downloads/sqlgraph-mcp-macos-arm64.zip sh install.sh
> ```

**npm (Node.js 18+)**
```bash
npx -y sqlgraph-mcp install          # 설치 없이 바로 Claude Code 에 등록
# 또는
npm install -g sqlgraph-mcp && sqlgraph-mcp install
```
처음 실행할 때 OS 에 맞는 배포 파일(Java 런타임 포함)을 `~/.sqlgraph/versions/<버전>` 에 한 번 내려받습니다.
Claude Code 에는 `npx -y sqlgraph-mcp serve`(npx) 또는 전역 설치 경로로 등록됩니다.

Java 가 이미 있다면 Releases 의 `sqlgraph-mcp-<버전>-all.jar` 하나로도 됩니다 (JDK 25 필요):
```bash
java -jar sqlgraph-mcp-0.4.0-all.jar install
```

## 사용

Claude Code 에서 말로 하면 됩니다.

| 이렇게 말하면 | 호출되는 도구 |
|---|---|
| "C:\work\hr-system DB 인덱싱해줘" | `index_project` |
| "발령 관련 테이블 뭐 있어?" | `search_table` |
| "HR_EMP 구조랑 쓰는 곳 보여줘" | `describe_table` |
| "EMAIL 컬럼 길이 바꾸면 영향 범위?" | `impact_analysis` |
| "EmpMapper.selectEmpList 쿼리 보여줘" | `find_query` |
| "HR_APPNT 에서 CM_CODE 까지 어떻게 조인해?" | `find_join_path` |
| "인덱싱한 프로젝트 목록" | `list_projects` |
| "인덱스 상태 확인해줘" | `index_status` |

- 여러 프로젝트를 인덱싱해 두고 이름으로 오갈 수 있습니다 ("pay-system 에서 PAY_MONTH 보여줘").
- Mapper 나 스키마가 바뀌면 "다시 인덱싱해줘" 라고 하면 됩니다 (자동 감지는 아직 없음).
- **FK 없는 관계**: 소스 쿼리의 조인 조건(`ON a.X = b.Y`, Oracle `(+)`, 상관 서브쿼리, 셀프 조인)에서 테이블 관계를 찾습니다.
  공통코드처럼 PK 일부를 상수로 고정하는 조인(`c.GRP_CD = 'POS' AND c.CD = e.POS_CD`)은 조건까지 함께 기록합니다.
  `describe_table` 에 FK·쿼리 근거 수와 참조 방향이 나오고, `find_join_path` 가 조인 SQL 을 만들어 줍니다.
- `impact_analysis` 의 "사용 가능성 있음"은 `SELECT *`, 테이블을 특정할 수 없는 동명 컬럼, `${}` 동적 SQL, 파싱 실패 쿼리입니다.

## DB 접속 정보는 어떻게 찾나

`index_project` 를 부르면 서버가 프로젝트 안의 설정 파일을 **직접** 읽습니다.
비밀번호가 Claude 대화를 거치지 않고, 도구 응답·로그에도 나오지 않습니다.

| 대상 | 읽는 것 |
|---|---|
| Spring Boot | `application*.yml` / `application*.properties` 의 `spring.datasource.*`, `*.jdbc-url` (다중 datasource), `${ENV:기본값}` |
| `.env` | `DATABASE_URL` (`postgres://`, `mysql://` …), Laravel 식 `DB_CONNECTION` / `DB_HOST` / `DB_DATABASE` / `DB_USERNAME` / `DB_PASSWORD` |
| Java 레거시 | `jdbc.properties`·eGov `globals.properties` 같은 `*.properties` 의 `*.url`/`username`/`password`, Spring XML `<bean>`·`persistence.xml`·`hibernate.cfg.xml`·`mybatis-config.xml` 의 property (`${..}` 는 프로젝트 properties 로 해석), Tomcat `context.xml` `<Resource>` |
| Django | `settings.py`, `settings/local.py` … 의 `DATABASES` (`os.environ.get('X', '기본값')` 해석) |
| Rails | `config/database.yml` (ERB `ENV['X']`, `ENV.fetch` 해석, 다중 DB) |
| Node | Prisma `schema.prisma` (`env("DATABASE_URL")` → `.env`), Sequelize `config/config.json`, TypeORM `ormconfig.json` |
| .NET | `appsettings*.json` 의 `ConnectionStrings` (SQL Server / PostgreSQL / MySQL 연결 문자열) |

- **프로필 선택**: 같은 DB 설정이 여러 프로필에 있으면 `local` → `dev` → 기본 설정 순으로 고릅니다.
  **`prod` 등 다른 프로필만 있으면 운영 DB 보호를 위해 접속하지 않습니다.** 필요하면 "prod 프로필로 인덱싱해줘".
  다른 스택도 같은 규칙입니다: 파일명(`settings/production.py`, `appsettings.Production.json`), 환경 키(`production:`),
  경로(`config/prod/jdbc.properties`, `*-real.properties`)가 운영을 가리키면 자동으로 고르지 않습니다.
- `src/test` 아래 설정과 H2 등 메모리 DB 는 무시합니다. `log4jdbc`, `p6spy` 래퍼 URL 은 원래 URL 로 바꿔 접속합니다.
- 비밀번호가 암호화(jasypt `ENC(...)`)돼 있거나 `${...}` 를 풀 수 없으면 환경변수로 지정합니다:
  `SQLGRAPH_DB_PW` (모든 DB) 또는 `SQLGRAPH_DB_PW_<DB이름>` (예: `SQLGRAPH_DB_PW_HR`).
- 접속 정보를 못 찾으면 **소스의 쿼리만** 인덱싱합니다. DB 접속 없이 하려면 "DB 접속 없이 인덱싱해줘" (`skip_db`).
- DB 메타데이터(테이블·컬럼·코멘트·PK·FK)만 읽고 **데이터는 읽지 않습니다**. 읽기 전용 계정을 권장합니다.
- 접속 대기 시간은 10초입니다 (방화벽 등으로 응답이 없을 때 오래 멈추지 않도록).

### JDBC 드라이버

- 내장: MariaDB (MySQL 겸용), PostgreSQL, Oracle (ojdbc11), SQL Server
- 내장되지 않은 드라이버(MySQL Connector/J, H2, DB2 …)는 **처음 접속할 때 Maven Central 에서 자동으로 받아**
  `~/.sqlgraph/drivers` 에 둡니다 (SHA-1 검증).
- Maven Central 에 없는 드라이버(Tibero, Altibase 등)나 폐쇄망은 `sqlgraph.yml` 의 `driverJar` 로 지정합니다.
  DBeaver 가 받아 둔 jar 를 지정해도 됩니다.

### sqlgraph.yml (선택)

자동 탐지가 안 되는 경우에만 프로젝트 루트에 둡니다 (`sqlgraph init` 으로 예시 생성).

```yaml
project: .
databases:
  - id: hr
    type: jdbc
    url: jdbc:tibero:thin:@dbhost:8629:tibero
    user: reader
    passwordEnv: HR_DB_PW            # 비밀번호는 환경변수로 (password: 직접 지정도 가능)
    driverJar: ./drivers/tibero7-jdbc.jar
  - id: pay                          # Oracle — 스키마(소유자) 지정
    type: jdbc
    url: jdbc:oracle:thin:@//dbhost:1521/ORCL
    user: reader
    passwordEnv: PAY_DB_PW
    schema: PAY
  - id: ext                          # DB 접속이 안 되는 환경: DDL 파일로 (mysqldump, Oracle export)
    type: ddl
    files: ["ddl/*.sql"]
    dialect: oracle
# autodetect: true                   # databases 가 있으면 기본 false. 자동 탐지도 같이 하려면 true
# profile: local                     # 자동 탐지 프로필
# index: .sqlgraph/graph.db          # 인덱스를 프로젝트 안에 두고 싶을 때 (.gitignore 필수)
sources:
  include: ["**/*.xml", "**/*.sql"]
  exclude: ["**/test/**"]
```

## 데이터 위치

```
~/.sqlgraph/                       (환경변수 SQLGRAPH_HOME 으로 변경)
  app/                             설치된 프로그램 (설치 스크립트 사용 시)
  projects.json                    인덱싱한 프로젝트 목록
  indexes/<프로젝트>-<해시>/graph.db  프로젝트별 인덱스 (SQLite)
  drivers/                         자동으로 받은 JDBC 드라이버
```

인덱스를 프로젝트 폴더 밖에 두기 때문에 회사 저장소에 DB 구조가 커밋될 걱정이 없습니다.

## CLI

```bash
sqlgraph index C:\work\hr-system              # 인덱싱 (--profile dev, --skip-db)
sqlgraph call describe_table '{"table":"HR_EMP"}'
sqlgraph call impact_analysis '{"table":"HR_EMP","column":"DEPT_CD","project":"hr-system"}'
sqlgraph install                               # Claude Code 등록 (--dry-run 으로 명령만 확인)
sqlgraph uninstall                             # 등록 해제
sqlgraph serve                                 # MCP 서버 (Claude Code 가 실행)
sqlgraph init                                  # sqlgraph.yml 예시 생성
```

`claude` CLI 가 없으면 `sqlgraph install` 이 수동 등록 명령과 Claude Desktop 설정 예시를 출력합니다.

## 동작 방식

```
[DB 커넥터]                    [SQL 추출기]                    [그래프 (SQLite)]
설정 자동 탐지 → JDBC 메타데이터  MyBatis 3 Mapper XML            테이블 · 컬럼 · 코멘트 · FK
DDL 파일 (접속 불가 환경)         iBatis 2 SqlMap XML             쿼리 ─ 사용 테이블 (읽기/쓰기)
                               .sql 파일                       쿼리 ─ 사용 컬럼 (읽기/쓰기)
                                                              쿼리 ─ Mapper id · 파일:줄
```

1. **DB 메타데이터 수집**: JDBC `DatabaseMetaData` 로 테이블·컬럼·코멘트·PK·FK 만 읽습니다.
2. **SQL 추출**: Mapper XML 의 동적 태그(`if`, `where`, `set`, `foreach`, `include` …)를 평탄화해 분석 가능한 SQL 로 만듭니다.
3. **SQL 분석**: JSqlParser 로 파싱해 테이블·컬럼과 읽기/쓰기 여부, 별칭을 해석합니다.
   `WHERE EMP_NO = ?` 처럼 테이블이 지정되지 않은 컬럼은 DB 메타데이터로 어느 테이블 컬럼인지 확정합니다.
   파싱에 실패한 쿼리도 정규식으로 테이블은 뽑아 둡니다.
4. **저장**: SQLite 에 저장. 도구는 이 파일만 조회하므로 보통 수십 ms 안에 응답합니다.

## 분석 규칙과 한계

- **동적 SQL**: 모든 `<if>` 가 참이라고 보고 펼칩니다. `<choose>` 는 **모든 `<when>`/`<otherwise>` 분기**를 각각 펼쳐 분석하고 결과(테이블·컬럼·조인)를 합칩니다. 화면에 보이는 SQL 은 첫 번째 분기 기준입니다.
- **`<include>`**: 다른 Mapper 파일의 `<sql>` 조각도 찾아 펼치고, `<property>` 값으로 조각 안의 `${이름}` 을 바꿉니다. 끝내 못 찾은 조각만 "동적"으로 표시됩니다.
- **`${...}`**: 런타임 값이라 분석에서 제외하고 "동적"으로 표시합니다.
- **프로시저 호출**(`{call ...}`)은 쿼리로 등록하지만 프로시저 내부 테이블은 분석하지 않습니다.
- **관계 추론**: 조인 조건이 있는 쿼리가 소스에 있어야 찾습니다. 같은 대상 컬럼으로 가는 여러 관계(발령 전/후 부서 등)는
  하나로 묶지 않고 별개 관계로 표시합니다. 프로시저 본문 안의 조인은 아직 분석하지 않습니다.
- **배타적인 `<if>`**: 같은 변수를 서로 다른 값과 비교하는 연속된 `<if>`(`x == "Y"` / `x == null or x == ''`)는
  동시에 참일 수 없으므로 `<choose>` 처럼 분기별로 펼쳐 분석합니다.
- **주석**: SQL 안의 `--`, MySQL `#` 주석은 블록 주석으로 바꿔서 보관합니다 (한 줄로 합쳐도 뒤 SQL 이 주석이 되지 않음).
- **방언 보정**: 1차 파싱이 실패하면 MySQL `||`(OR), 숫자로 시작하는 컬럼명, `INTERVAL`, `DIV`, `DELETE t.* FROM`,
  예약어 별칭·테이블명, 백슬래시 이스케이프 등을 보정해서 다시 파싱합니다. 그래도 실패한 쿼리는
  `index_status` 에 유형·폴더별로 모아 보여 줍니다 ("파싱 실패 목록 50건 보여줘" → `failures`).
- **코드 속 SQL** (Java·Kotlin·Scala·Groovy·C#·JS/TS·Go·PHP·Python·Ruby):
  - 문자열 리터럴 중 SQL 문장 구조(SELECT…FROM, INSERT INTO, UPDATE…SET …)를 갖춘 것만 추출합니다. 영문 문장("Select a file from…")은 제외.
  - `"SELECT .." + "FROM .."`, `StringBuilder.append(..)` 체인, `sql += ".."` 누적을 한 문장으로 합칩니다.
    사이에 끼운 변수·문자열 보간(`${x}`, f-string, `$var`)은 동적 요소로 표시합니다.
  - 쿼리 id 는 `패키지.클래스.메서드` (Python/JS 등은 `파일.함수`). MyBatis 어노테이션(`@Select` …)과 `<script>` 도 지원.
  - 테스트 코드(`src/test`, `*Test.java`, `test_*.py`, `*.spec.ts` …)는 건너뜁니다.
- **JPA**: `@Entity`·`@Table`·`@Column`·`@JoinColumn`·`@Embedded`·`@MappedSuperclass` 매핑을 모아 `@Query`·`createQuery`·`@NamedQuery` 의
  JPQL 을 실제 테이블·컬럼 SQL 로 바꿔 분석합니다 (경로 조인 `JOIN e.dept d` → `ON e.DEPT_CD = d.DEPT_CD`, `SELECT e` → 전체 컬럼).
  이름을 지정하지 않은 엔티티·필드는 Spring Boot 기본 규칙(snake_case)을, `PhysicalNamingStrategyStandardImpl` 설정이 있으면 이름 그대로 씁니다.
  `nativeQuery = true`·`createNativeQuery` 는 SQL 그대로. Spring Data 메서드 이름 쿼리(`findByDeptCd`)는 아직 분석하지 않습니다.
- **테이블 매칭**은 이름 기준(대소문자 무시)입니다.

## 개발

```bash
./gradlew build                    # 빌드 + 테스트 → server/build/libs/sqlgraph-mcp-<버전>-all.jar
./gradlew :server:packageDist      # 지금 OS 용 배포 zip (Java 런타임 포함) → server/build/dist/
```

- JDK 25, Gradle Wrapper 9.1. 폐쇄망은 `libs/` 에 jar 를 넣고 `-Poffline` (`libs/README.md`).
- **릴리스**: `v*` 태그 푸시 또는 Actions → Release → Run workflow(태그 입력) → GitHub Actions(`release.yml`)가
  Windows x64 / macOS arm64 / Linux x64 배포 zip 과 단일 jar 를 만들어 Release 에 올립니다.
  `npm/` 패키지도 같은 버전으로 npm 에 배포합니다 (Trusted Publishing — GitHub Actions OIDC, 토큰 불필요).
- 확장 포인트: `io.sqlgraph.core.spi.SchemaSource` (DB 입력 형식), `io.sqlgraph.core.spi.SqlExtractor` (언어·프레임워크).
- MCP 프로토콜은 공식 SDK 없이 직접 구현했습니다 (stdio, JSON-RPC 2.0).
- JSqlParser 4.9 를 사용합니다. 5.x 는 Visitor 시그니처가 바뀌어 `SqlAnalyzer.Collector` 를 함께 고쳐야 합니다.
- **실제 DB 통합 테스트**: `.github/workflows/integration.yml` 이 Oracle 23 Free / SQL Server 2022 / PostgreSQL 16 / MySQL 8.4
  컨테이너에 스키마(한글 코멘트·PK·FK·뷰)를 만들고, 각 방언 Mapper 를 인덱싱해 확인합니다 (`server` 의 `RealDbIT`).
  로컬에서는 `SQLGRAPH_IT_<DB>_URL`/`_USER`/`_PASSWORD` 환경변수가 있을 때만 실행됩니다.

```
core/       모델, SPI, SqlAnalyzer(JSqlParser), SqlComments, GraphStore(SQLite), Indexer
connector/  JdbcSchemaSource, DdlSchemaSource, DriverResolver, detect/(설정 자동 탐지: Spring·.env·StackConfigs)
extractor/  MyBatisXmlExtractor, SqlFileExtractor, code/(코드 속 SQL·JPA: CodeLexer, CodeSqlExtractor, JpaModel)
server/     MCP 서버, 도구, Workspace(여러 프로젝트), CLI, Installer, 실행 스크립트(src/launcher)
npm/        npm 런처 (npx sqlgraph-mcp): OS 별 배포 zip 다운로드·캐시·실행
samples/    hr-project (sqlgraph.yml + DDL), spring-hr (application.yml 자동 탐지)
```

## 라이선스

MIT — [LICENSE](LICENSE)
