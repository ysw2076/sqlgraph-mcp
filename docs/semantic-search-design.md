# sqlgraph 의미 검색 설계 (초안)

대상: `sqlgraph-mcp-repo` 0.5.0 기준. 목표 버전 0.6.0 (실험 기능, 기본 꺼짐).

## 1. 왜 필요한가

지금 `search_table` 은 `GraphStore.searchTables()` 의 **LIKE 부분 일치**다.
공백으로 나눈 키워드가 모두 테이블명·코멘트(옵션: 컬럼명·컬럼 코멘트)에 들어 있어야 찾는다.

실제 회사 프로젝트(테이블 약 1천 / 컬럼 약 2만)에서 확인한 예:

| 검색어 | 결과 |
|---|---|
| `인사이동` | 0건 ("해당하는 테이블이 없습니다") |
| `발령` | 발령코드, 발령이력, 발령서 … 5건 |

같은 뜻인데 표현이 다르면 못 찾는다. 약어 테이블명(`APHIST` 같은)은 이름으로도 짐작이 안 된다.
의미 검색은 이 "입구"를 넓히는 것이고, 찾은 다음의 조인·쿼리·영향도는 기존 그래프가 그대로 맡는다 (GraphRAG).

> 정직한 한계: 호출하는 쪽이 LLM(Claude)이라 0건이면 스스로 동의어로 다시 찾기도 한다.
> 의미 검색의 실제 이득은 ① 왕복 횟수 감소 ② 컬럼 단위로 흩어진 의미(코멘트가 컬럼에만 있는 경우)
> ③ 코멘트 없는 테이블을 "쓰는 쿼리 이름"으로 찾기 — 이 셋이다. 4절 평가로 이득을 숫자로 확인한 뒤 기본값을 켠다.

## 2. 원칙

1. **외부로 아무것도 보내지 않는다.** 스키마·코멘트는 회사 자료다. 임베딩은 로컬에서만 계산한다.
   HTTP 공급자는 루프백(127.0.0.1/localhost)만 기본 허용.
2. **"SQLite 파일 하나" 구조를 지킨다.** 별도 Vector DB 서버를 두지 않는다. 벡터는 `graph.db` 안에.
3. **기본 배포 크기를 늘리지 않는다.** 모델·추론 런타임은 처음 켤 때 내려받는다 (`DriverResolver` 와 같은 방식, 해시 검증).
   폐쇄망은 파일 경로로 지정 (`driverJar` 와 같은 방식).
4. **꺼져 있거나 준비 전이면 지금과 똑같이 동작한다.** 의미 검색은 키워드 검색을 대체하지 않고 더한다.
5. **Vector DB 로 옮길 수 있게** 저장·검색을 인터페이스 뒤에 둔다 (6절 3단계).

## 3. 구성

```
index_project
  └ Indexer.run()  ── 기존: 메타데이터 → 소스 스캔 → SQL 분석 → GraphStore.replaceAll()
        └ (새) EmbeddingStep  ── 문서 만들기 → 캐시 비교 → 바뀐 것만 임베딩 → emb 저장
                                 백그라운드 스레드, 진행률은 index_status 에

search_table(keyword, mode=auto)
  ├ 키워드: 기존 searchTables()                       → 순위 A
  ├ 의미:   Embedder.embed(query) → VectorIndex.topK() → 순위 B (테이블/컬럼 → 테이블로 묶음)
  └ 합치기: 정확한 이름 일치 맨 위 + RRF(A, B)         → 결과 표에 "근거" 칸
```

### 3.1 모듈 배치

| 위치 | 새 클래스 | 역할 |
|---|---|---|
| `core/spi` | `Embedder` | `float[][] embed(List<String> texts, Kind kind)` · `String modelId()` · `int dim()`. Kind = QUERY / DOCUMENT (e5 계열 접두어 처리) |
| `core/semantic` | `DocBuilder` | 테이블·컬럼 → 임베딩할 문장 |
| `core/semantic` | `VectorIndex` | 메모리 적재 + 내적 top-K (전수 비교) |
| `core/semantic` | `HybridRanker` | 키워드 순위 + 의미 순위 → RRF |
| `core/store` | `GraphStore` 확장 | `emb`·`emb_cache` 읽기/쓰기, SCHEMA_VERSION 2 → 3 |
| `connector` (또는 새 `semantic` 모듈) | `OnnxEmbedder` | ONNX Runtime + 토크나이저, 모델 내려받기 |
| 같은 곳 | `HttpEmbedder` | Ollama `/api/embed`, OpenAI 호환 `/v1/embeddings` (루프백만) |
| `server` | `Tools`·`SqlGraphService`·`Config` | `mode` 인자, 설정, 상태 표시 |

### 3.2 무엇을 임베딩하나 (DocBuilder)

| 종류 | 문장 (예) | 비고 |
|---|---|---|
| 테이블 | `발령이력. 테이블 APHIST. 컬럼: 사번, 발령일자, 발령구분, 부서코드 …` | 코멘트 + 이름 + 앞쪽 컬럼 코멘트 (토큰 상한 안에서) |
| 컬럼 | `발령이력 > 발령일자 (APPNT_DT)` | 코멘트 있는 컬럼만. 테이블 코멘트를 앞에 붙여 문맥을 준다 |
| 코멘트 없는 테이블 | `테이블 TXDEDCL. 사용 쿼리: YearEndTaxMapper.selectDeduction, …` | `stmt.owner`(Mapper id) 상위 N개로 의미를 빌린다 |
| 메타데이터에 없는 테이블 | 위와 같이 쿼리 이름으로 | 지금 `unknownTables` 로만 보이는 것들도 검색에 걸리게 |

- 이름 분해: `APPNT_DT` → `APPNT DT`, `empNo` → `emp No`. 약어 사전은 1단계에서 안 만든다.
- 규모: 1천 테이블 + 코멘트 있는 컬럼 ≤ 2만 → 문서 ≤ 약 2.1만 개.

### 3.3 저장 (graph.db)

```sql
-- 모델이 바뀌면 전부 무효: meta 에 emb_model, emb_dim, emb_doc_version
CREATE TABLE emb_cache(              -- replaceAll() 에서 지우지 않는다
  hash TEXT PRIMARY KEY,             -- sha256(model + doc_version + 문장)
  vec  BLOB);                        -- float32 리틀엔디언, L2 정규화된 값
CREATE TABLE emb(                    -- replaceAll() 때 비우고 EmbeddingStep 이 다시 채운다
  kind TEXT,                         -- 't' | 'c'
  tbl_id INTEGER, col_id INTEGER,    -- col_id 는 컬럼일 때만
  hash TEXT);
CREATE INDEX ix_emb_tbl ON emb(tbl_id);
```

- `replaceAll()` 은 매번 `tbl`/`col` 을 지우고 id 를 1부터 다시 매긴다 → id 로 캐시하면 다 깨진다.
  그래서 **문장 해시로 캐시**한다. 재인덱싱 때 바뀐 문장만 임베딩, 나머지는 캐시 재사용.
- 다 쓰고 나면 `emb` 가 참조하지 않는 `emb_cache` 행은 지운다.
- 크기: 384차원 × 4바이트 × 2.1만 ≈ 32MB. 지금 그 회사 프로젝트의 `graph.db` 가 약 31MB 이므로 두 배 정도.
  줄여야 하면 int8 양자화(¼) — 1단계에서는 하지 않는다.

### 3.4 검색 (VectorIndex · HybridRanker)

- 첫 의미 검색 때 `emb` + `emb_cache` 를 메모리 `float[]` 하나로 적재 (프로젝트별, 인덱스 갱신 시 버림).
- 2.1만 × 384 내적 ≈ 800만 곱셈 → 수 ms. **ANN 인덱스·sqlite-vec 불필요** (sqlite-vec 은 OS별 네이티브 확장 배포 문제도 있다).
- 컬럼 결과는 소속 테이블로 묶고 가장 높은 점수 하나만 쓴다. 표에 "근거: 발령일자(컬럼) 0.82" 처럼 남긴다.
- 합치기: ① `name_key` 정확 일치 맨 위 ② 나머지는 RRF `Σ 1/(60 + rank)` ③ 의미 점수가 문턱(예: 0.75, 4절에서 정함) 미만이면 버림.
- 결과 표에 칸 추가: `근거` = `이름` / `키워드` / `의미 0.82 (발령일자)`. LLM 이 왜 나왔는지 알게.

### 3.5 MCP 도구

새 도구를 만들지 않고 `search_table` 을 넓힌다 (도구 수를 늘리면 선택 오류가 는다).

| 인자 | 값 | 동작 |
|---|---|---|
| `mode` (새) | `auto` (기본) | 임베딩이 준비됐으면 하이브리드, 아니면 지금과 같음 |
| | `keyword` | 지금과 똑같이 |
| | `semantic` | 의미만 |
| `include_columns` | 그대로 | 하이브리드에서는 컬럼 의미 검색도 켬 |

- 0건일 때 안내문: 의미 검색이 꺼져 있으면 "sqlgraph.yml 의 semantic 으로 켤 수 있습니다" 한 줄.
- `index_status` 에 `의미 검색: 모델 multilingual-e5-small · 문서 21,032 · 준비됨` / `준비 중 12,000/21,032` / `꺼짐`.

### 3.6 설정 (sqlgraph.yml)

```yaml
semantic:
  enabled: true                    # 0.6.0 기본 false
  provider: onnx                   # onnx | http
  model: multilingual-e5-small     # onnx: 처음 켤 때 ~/.sqlgraph/models 로 내려받기 (해시 검증)
  # modelPath: D:/models/e5-small  # 폐쇄망: model.onnx + tokenizer.json 이 든 폴더
  # provider: http
  # url: http://127.0.0.1:11434/api/embed   # Ollama
  # model: bge-m3
  # allowRemote: false             # 루프백이 아닌 주소는 이 값을 true 로 해야만 허용
```

## 4. 모델·런타임 선택 (설계 확인 항목)

| 후보 | 크기 (int8) | 차원 | 한국어 | 판단 |
|---|---|---|---|---|
| **multilingual-e5-small** | 약 120MB | 384 | 좋음 | **1순위.** 크기·품질 균형. 쿼리/문서 접두어(`query:` / `passage:`) 필요 |
| paraphrase-multilingual-MiniLM-L12-v2 | 약 120MB | 384 | 보통 | 대안 |
| bge-m3 | 약 570MB+ | 1024 | 매우 좋음 | ONNX 내장으로는 무겁다. **HTTP(Ollama) 공급자로만** |

런타임: ONNX Runtime Java (`com.microsoft.onnxruntime:onnxruntime`).
토크나이저: e5/MiniLM 은 XLM-R 계열 SentencePiece(Unigram).

**확인할 위험** (구현 전에 작은 시험으로):

- R1. ONNX Runtime jar 를 `URLClassLoader` 로 늦게 불러도 네이티브 라이브러리가 풀리는가 (기본 배포에 넣지 않기 위해).
  안 되면 → 별도 배포 zip(`sqlgraph-mcp-semantic-<os>.zip`) 또는 jar 에 포함하고 크기 증가를 받아들임.
- R2. 토크나이저: DJL `tokenizers`(네이티브) vs 순수 Java Unigram 구현(`tokenizer.json` 읽기, 약 200줄).
  네이티브를 하나 더 늘리지 않으려면 순수 Java. 같은 입력에 HF 토크나이저와 토큰 id 가 같은지 시험으로 확인.
- R3. jlink 런타임 모듈 목록(`runtimeModules`)에 추가할 것이 있는가.
- R4. CPU 임베딩 속도. 목표: 2.1만 문서 첫 인덱싱 3분 이내, 재인덱싱(변경 1%) 10초 이내.
- R5. 폐쇄망 빌드(`-Poffline`, `libs/`)에서 ONNX Runtime jar 를 어떻게 공급할지.

## 5. 평가 (기본값을 켜기 전 게이트)

- **공개 평가셋**: `samples/hr-project` 스키마로 질의 30개 이상 (동의어·약어·컬럼 의미·코멘트 없음 4종).
  각 질의에 정답 테이블. 저장소에 커밋해 CI 에서 돈다.
- **비공개 평가셋**: 회사 프로젝트용 질의는 저장소에 넣지 않고 로컬 파일로만 (`~/.sqlgraph/eval/<프로젝트>.yml`).
- 지표: Recall@5, MRR. `keyword` / `semantic` / `auto` 세 모드 비교.
- 통과 기준(제안): `auto` 가 `keyword` 보다 Recall@5 +20%p 이상, 정확한 이름 검색 회귀 0건, 검색 지연 p95 50ms 이하.
- 문턱값(3.4 ③)과 RRF 상수는 이 평가로 정한다.

## 6. 단계

| 단계 | 내용 | 끝 조건 |
|---|---|---|
| **S0 설계 확인** | R1~R5 작은 시험, 모델 확정, 평가셋 초안 | 각 위험에 "됨/안 됨/대안" 기록 |
| **S1 저장·검색 골격** | `Embedder` SPI, `emb`/`emb_cache`, `DocBuilder`, `VectorIndex`, 가짜 Embedder(해시 기반)로 테스트 | 단위 테스트, 재인덱싱 캐시 적중 테스트 |
| **S2 ONNX 공급자** | 내려받기·해시 검증·폐쇄망 경로, 백그라운드 EmbeddingStep, `index_status` 진행률 | hr-project 로 실제 임베딩, R4 목표 |
| **S3 하이브리드 검색** | `mode` 인자, RRF, 근거 칸, 안내문 | 5절 평가 통과 → 0.6.0 (기본 꺼짐) |
| S4 HTTP 공급자 | Ollama/OpenAI 호환, 루프백 제한 | bge-m3 로 같은 평가 |
| S5 기본값 켜기 검토 | 사용 기록·평가 보고 결정 | — |
| (나중) 확장 | 쿼리 SQL 자체 임베딩(`find_query` 의미 검색), int8 양자화, 50만+ 문서면 sqlite-vec/전용 Vector DB 로 `VectorIndex` 교체 | — |

## 7. 하지 않는 것 (이번 범위 밖)

- 외부 임베딩 API (OpenAI 등) 기본 지원 — 회사 자료 반출 위험. 원하면 `http` + `allowRemote: true` 로 사용자가 명시적으로.
- 별도 Vector DB 서버 (Qdrant, Chroma …).
- LLM 호출 (질의 재작성, 요약) — sqlgraph 는 검색만, 판단은 호출한 Claude 가.
- 약어 사전, 한국어 형태소 분석.
