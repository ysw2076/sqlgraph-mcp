# sqlgraph-mcp

**English** | [한국어](README.ko.md)

A local MCP server that indexes your DB schema (tables, columns, comments, PKs, FKs)
and the SQL in your source code (MyBatis/iBatis mapper XML, `.sql` files),
so AI coding tools like Claude Code can look them up **in one call — no grep needed**.

```
You:    Index the DB for C:\work\hr-system
Claude: (index_project) Found MariaDB in application.yml — indexed 214 tables and 1,840 queries.
You:    What's affected if I change HR_EMP.DEPT_CD?
Claude: (impact_analysis) 3 writes, 12 reads, plus 4 possible usages (SELECT * etc.) … (mapper id · file:line)
```

## Install

No separate Java install required — each release bundles a lightweight Java runtime.

**Windows (PowerShell)**
```powershell
irm https://raw.githubusercontent.com/ysw2076/sqlgraph-mcp/main/install.ps1 | iex
```

**macOS / Linux**
```bash
curl -fsSL https://raw.githubusercontent.com/ysw2076/sqlgraph-mcp/main/install.sh | sh
```

What the install script does:
1. Downloads the release for your OS from GitHub Releases into `~/.sqlgraph/app`
2. Adds the `sqlgraph` command to your PATH
3. Runs `sqlgraph install` to **register the MCP server with Claude Code** (available in every project, `--scope user`)

Restart Claude Code and check that `sqlgraph` shows as connected under `/mcp`. That's it.

> On a network without internet access, download the zip for your OS from the Releases page and point `SQLGRAPH_ZIP` at it.
> ```powershell
> $env:SQLGRAPH_ZIP = "$HOME\Downloads\sqlgraph-mcp-windows-x64.zip"; .\install.ps1
> ```
> ```bash
> SQLGRAPH_ZIP=~/Downloads/sqlgraph-mcp-macos-arm64.zip sh install.sh
> ```

**npm (Node.js 18+)**
```bash
npx -y sqlgraph-mcp install          # register with Claude Code without installing anything globally
# or
npm install -g sqlgraph-mcp && sqlgraph-mcp install
```
On first run it downloads the release for your OS (Java runtime included) once into `~/.sqlgraph/versions/<version>`.
Claude Code is registered with `npx -y sqlgraph-mcp serve` (npx) or the global install path.

If you already have Java, the single `sqlgraph-mcp-<version>-all.jar` from Releases is enough (requires JDK 25):
```bash
java -jar sqlgraph-mcp-0.4.0-all.jar install
```

## Usage

Just ask Claude Code in plain language.

| Say this | Tool called |
|---|---|
| "Index the DB for C:\work\hr-system" | `index_project` |
| "What tables are there for personnel transfers?" | `search_table` |
| "Show me HR_EMP's structure and where it's used" | `describe_table` |
| "What's affected if I change the length of the EMAIL column?" | `impact_analysis` |
| "Show me the EmpMapper.selectEmpList query" | `find_query` |
| "How do I join HR_APPNT to CM_CODE?" | `find_join_path` |
| "List the indexed projects" | `list_projects` |
| "Check the index status" | `index_status` |

- You can index several projects and switch between them by name ("show me PAY_MONTH in pay-system").
- When mappers or the schema change, just say "re-index" (no automatic change detection yet).
- **Relationships without FKs**: table relationships are discovered from join conditions in your source queries
  (`ON a.X = b.Y`, Oracle `(+)`, correlated subqueries, self joins).
  Joins that pin part of a PK to a constant, as with common-code tables (`c.GRP_CD = 'POS' AND c.CD = e.POS_CD`), are recorded together with that condition.
  `describe_table` shows the number of FK / query evidences and the reference direction, and `find_join_path` builds the join SQL for you.
- "Possible usages" in `impact_analysis` are `SELECT *`, same-named columns whose table can't be determined, `${}` dynamic SQL, and queries that failed to parse.

## How DB connection settings are found

When `index_project` is called, the server reads the configuration files in the project **itself**.
Passwords never pass through the Claude conversation and never appear in tool responses or logs.

| Source | What is read |
|---|---|
| Spring Boot | `spring.datasource.*` and `*.jdbc-url` (multiple datasources) in `application*.yml` / `application*.properties`, `${ENV:default}` |
| `.env` | `DATABASE_URL` (`postgres://`, `mysql://` …), Laravel-style `DB_CONNECTION` / `DB_HOST` / `DB_DATABASE` / `DB_USERNAME` / `DB_PASSWORD` |

- **Profile selection**: if the same DB is configured in several profiles, it picks `local` → `dev` → default, in that order.
  **If only `prod` or other profiles exist, it will not connect, to protect production databases.** If you need it, say "index with the prod profile".
- Configs under `src/test` and in-memory DBs such as H2 are ignored. `log4jdbc` and `p6spy` wrapper URLs are converted back to the original URL.
- If a password is encrypted (jasypt `ENC(...)`) or a `${...}` can't be resolved, set it via an environment variable:
  `SQLGRAPH_DB_PW` (all DBs) or `SQLGRAPH_DB_PW_<DB name>` (e.g. `SQLGRAPH_DB_PW_HR`).
- If no connection settings are found, **only the queries in the source** are indexed. To index without connecting to a DB, say "index without DB access" (`skip_db`).
- Only DB metadata (tables, columns, comments, PKs, FKs) is read — **never the data itself**. A read-only account is recommended.
- The connection timeout is 10 seconds (so it doesn't hang when a firewall swallows the connection).

### JDBC drivers

- Bundled: MariaDB (also works for MySQL), PostgreSQL, Oracle (ojdbc11), SQL Server
- Other drivers (MySQL Connector/J, H2, DB2 …) are **downloaded automatically from Maven Central on first connection**
  into `~/.sqlgraph/drivers` (SHA-1 verified).
- For drivers not on Maven Central (Tibero, Altibase, etc.) or air-gapped networks, set `driverJar` in `sqlgraph.yml`.
  A jar already downloaded by DBeaver works too.

### sqlgraph.yml (optional)

Only needed at the project root when auto-detection doesn't work (`sqlgraph init` generates an example).

```yaml
project: .
databases:
  - id: hr
    type: jdbc
    url: jdbc:tibero:thin:@dbhost:8629:tibero
    user: reader
    passwordEnv: HR_DB_PW            # password via environment variable (password: also works)
    driverJar: ./drivers/tibero7-jdbc.jar
  - id: pay                          # Oracle — specify the schema (owner)
    type: jdbc
    url: jdbc:oracle:thin:@//dbhost:1521/ORCL
    user: reader
    passwordEnv: PAY_DB_PW
    schema: PAY
  - id: ext                          # no DB access: use DDL files (mysqldump, Oracle export)
    type: ddl
    files: ["ddl/*.sql"]
    dialect: oracle
# autodetect: true                   # defaults to false when databases is set; true to also auto-detect
# profile: local                     # profile used for auto-detection
# index: .sqlgraph/graph.db          # keep the index inside the project (add it to .gitignore!)
sources:
  include: ["**/*.xml", "**/*.sql"]
  exclude: ["**/test/**"]
```

## Where data is stored

```
~/.sqlgraph/                       (override with the SQLGRAPH_HOME environment variable)
  app/                             installed program (when using the install script)
  projects.json                    list of indexed projects
  indexes/<project>-<hash>/graph.db  per-project index (SQLite)
  drivers/                         automatically downloaded JDBC drivers
```

Because the index lives outside your project folder, there's no risk of committing your DB structure to the company repository.

## CLI

```bash
sqlgraph index C:\work\hr-system              # index (--profile dev, --skip-db)
sqlgraph call describe_table '{"table":"HR_EMP"}'
sqlgraph call impact_analysis '{"table":"HR_EMP","column":"DEPT_CD","project":"hr-system"}'
sqlgraph install                               # register with Claude Code (--dry-run to just print the command)
sqlgraph uninstall                             # unregister
sqlgraph serve                                 # MCP server (launched by Claude Code)
sqlgraph init                                  # generate an example sqlgraph.yml
```

If the `claude` CLI isn't available, `sqlgraph install` prints the manual registration command and an example Claude Desktop config.

> CLI and tool output messages are currently in Korean. Claude reads them fine and will answer in your language.

## How it works

```
[DB connector]                       [SQL extractor]               [Graph (SQLite)]
config auto-detect → JDBC metadata   MyBatis 3 mapper XML          tables · columns · comments · FKs
DDL files (no DB access)             iBatis 2 SqlMap XML           query ─ tables used (read/write)
                                     .sql files                    query ─ columns used (read/write)
                                                                   query ─ mapper id · file:line
```

1. **Collect DB metadata**: reads only tables, columns, comments, PKs and FKs via JDBC `DatabaseMetaData`.
2. **Extract SQL**: flattens dynamic tags in mapper XML (`if`, `where`, `set`, `foreach`, `include` …) into analyzable SQL.
3. **Analyze SQL**: parses with JSqlParser to resolve tables, columns, read/write access and aliases.
   Columns without a table qualifier (e.g. `WHERE EMP_NO = ?`) are resolved to their table using the DB metadata.
   Even for queries that fail to parse, tables are still extracted with regular expressions.
4. **Store**: saved to SQLite. The tools only query this file, so responses usually take tens of milliseconds.

## Analysis rules and limitations

- **Dynamic SQL**: every `<if>` is treated as true and expanded. For `<choose>`, **every `<when>`/`<otherwise>` branch** is expanded and analyzed separately, and the results (tables, columns, joins) are merged. The SQL shown is based on the first branch.
- **`<include>`**: `<sql>` fragments in other mapper files are found and expanded too, and `${name}` inside a fragment is replaced with its `<property>` value. Only fragments that can't be found at all are marked as "dynamic".
- **`${...}`**: runtime values, so they're excluded from analysis and marked as "dynamic".
- **Procedure calls** (`{call ...}`) are registered as queries, but tables inside the procedure aren't analyzed.
- **Relationship inference**: requires a query with the join condition to exist in the source. Multiple relationships to the same target column
  (e.g. department before/after a transfer) are shown as separate relationships rather than merged. Joins inside procedure bodies aren't analyzed yet.
- **Supported sources**: MyBatis/iBatis XML, `.sql`. JPA `@Query`, SQL strings in code and ORM models are planned.
- **Table matching** is by name (case-insensitive).

## Development

```bash
./gradlew build                    # build + test → server/build/libs/sqlgraph-mcp-<version>-all.jar
./gradlew :server:packageDist      # distribution zip for the current OS (Java runtime included) → server/build/dist/
```

- JDK 25, Gradle Wrapper 9.1. For air-gapped networks, put the jars in `libs/` and use `-Poffline` (`libs/README.md`).
- **Release**: push a `v*` tag or run Actions → Release → Run workflow (enter the tag). GitHub Actions (`release.yml`) builds
  the Windows x64 / macOS arm64 / Linux x64 distribution zips and the single jar and uploads them to the Release.
  The `npm/` package is published to npm with the same version (Trusted Publishing via GitHub Actions OIDC — no token needed).
- Extension points: `io.sqlgraph.core.spi.SchemaSource` (DB input formats), `io.sqlgraph.core.spi.SqlExtractor` (languages/frameworks).
- The MCP protocol is implemented directly, without the official SDK (stdio, JSON-RPC 2.0).
- Uses JSqlParser 4.9. Version 5.x changed the visitor signatures, so `SqlAnalyzer.Collector` would need to be updated as well.

```
core/       model, SPI, SqlAnalyzer (JSqlParser), GraphStore (SQLite), Indexer
connector/  JdbcSchemaSource, DdlSchemaSource, DriverResolver, detect/ (config auto-detection)
extractor/  MyBatisXmlExtractor, SqlFileExtractor
server/     MCP server, tools, Workspace (multiple projects), CLI, Installer, launch scripts (src/launcher)
npm/        npm launcher (npx sqlgraph-mcp): downloads, caches and runs the per-OS distribution zip
samples/    hr-project (sqlgraph.yml + DDL), spring-hr (application.yml auto-detection)
```

## License

MIT — [LICENSE](LICENSE)
