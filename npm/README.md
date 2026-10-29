# sqlgraph-mcp

DB 스키마(테이블·컬럼·코멘트·PK·FK)와 소스 속 SQL(MyBatis/iBatis Mapper XML, `.sql`)을 인덱싱해 두고,
Claude Code 같은 AI 코딩 도구가 grep 없이 바로 조회하게 해 주는 로컬 MCP 서버입니다.

```bash
# Claude Code 에 바로 등록 (Java 설치 불필요 — 처음 실행 때 런타임 포함 배포 파일을 한 번 내려받음)
npx -y sqlgraph-mcp install

# 또는 전역 설치
npm install -g sqlgraph-mcp
sqlgraph-mcp install
```

Claude Code 를 새로 열고 이렇게 말하면 됩니다.

> C:\work\hr-system DB 인덱싱해줘
> HR_EMP.DEPT_CD 바꾸면 영향받는 곳은?
> HR_APPNT 에서 CM_CODE 까지 어떻게 조인해?

- DB 접속 정보는 `application.yml`, `.env` 에서 자동으로 찾습니다 (비밀번호는 대화에 나오지 않음).
- FK 가 없어도 소스 쿼리의 조인 조건에서 테이블 관계를 찾습니다.
- 지원 OS: Windows x64, macOS (Apple Silicon), Linux x64. 데이터는 `~/.sqlgraph` 에 저장됩니다.
- 네트워크가 막힌 환경: Releases 에서 OS 에 맞는 zip 을 받아 `SQLGRAPH_ZIP` 환경변수로 지정하세요.

자세한 내용: https://github.com/ysw2076/sqlgraph-mcp
