# sqlgraph-mcp

**English** | [한국어](https://github.com/ysw2076/sqlgraph-mcp/blob/main/README.ko.md)

A local MCP server that indexes your DB schema (tables, columns, comments, PKs, FKs) and the SQL in your source code
(MyBatis/iBatis mapper XML, `.sql` files), so AI coding tools like Claude Code can look them up directly — no grep needed.

```bash
# Register with Claude Code right away (no Java install needed — the first run downloads a release with a bundled runtime once)
npx -y sqlgraph-mcp install

# or install globally
npm install -g sqlgraph-mcp
sqlgraph-mcp install
```

Restart Claude Code and ask things like:

> Index the DB for C:\work\hr-system
> What's affected if I change HR_EMP.DEPT_CD?
> How do I join HR_APPNT to CM_CODE?

- DB connection settings are found automatically in `application.yml` and `.env` (passwords never appear in the conversation).
- Table relationships are discovered from join conditions in your source queries, even without FKs.
- Supported OS: Windows x64, macOS (Apple Silicon), Linux x64. Data is stored in `~/.sqlgraph`.
- No internet access: download the zip for your OS from Releases and point the `SQLGRAPH_ZIP` environment variable at it.

More: https://github.com/ysw2076/sqlgraph-mcp
