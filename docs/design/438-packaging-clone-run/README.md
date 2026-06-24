# 설계서: clone & run 패키징 (#438)

> **상태**: Approved · **작성**: [AI] Architect · 2026-06-25 · **유형**: 운영/패키징
> **추적성** — Redmine: #438 · 기반: #416(universe)·#419(일봉)·#425(뉴스) · 구현: `config/SchemaInitializer`, `market/UniverseSeeder`, `news/{NewsIngestService,NewsClassifier,NewsProperties}`, `application.yml`, `.env.example`, `README.md`, `mvnw`

## 1. 목적
다른 사람이 **저장소를 clone → 토스 키 + Oracle DB(+선택 LLM)만 채우면 바로 구동**되게 한다. 별도 DDL 실행·로컬 Maven 설치 없이.

## 2. 인수조건
- [ ] 테이블이 없으면 **기동 시 자동 생성**(멱등), 있으면 그대로.
- [ ] 유니버스 자동 시드, **뉴스 기동 직후 자동 수집**(LLM 키 있을 때).
- [ ] 뉴스 LLM 은 **OpenAI 호환이면 무엇이든**(OpenAI/OpenRouter/로컬) — base-url·api-key·model 지정.
- [ ] LLM 키 없어도 핵심 기능(탑50·워치리스트·보유·시세) 정상.
- [ ] `./mvnw` 로 Java만 있으면 빌드/구동. 포트 환경변수화.

## 3. 설계
### 3.1 스키마 자동생성 — `SchemaInitializer`
- `ApplicationRunner`, `@Profile("default")`, `@Order(0)`, `@ConditionalOnProperty(app.schema.auto-init, matchIfMissing=true)`.
- `db/*.sql`(PL/SQL 익명블록, `/` 구분, ORA-00955/-01430 자체무시)을 `JdbcTemplate` 으로 순차 실행. 순서: `top50`(universe)→`daily_ohlcv`(universe ALTER)→`watchlist`→`holding`→`stock_news`→`us_universe`.
- 주석라인 제거 후 블록 단위 실행. 반복 실행 안전(멱등).
- `UniverseSeeder` 는 `@Order(20)` 으로 스키마 생성 뒤 시드.

### 3.2 뉴스 자동수집
- `@Scheduled(initialDelay=5s)` → 기동 직후 1회 + 주기. `running` 가드 + `existsByExtId` dedup.
- 스키마는 `@Order(0)` 러너가 ApplicationReadyEvent/스케줄 발화 전에 생성하므로 insert 안전.
- LLM 키 없으면 수집 스킵 + 안내 로그(핵심기능 무관).

### 3.3 LLM 일반화(OpenAI 호환)
- `NewsProperties`: `llmApiKey/llmModel/llmBaseUrl`.
- `NewsClassifier.chatUrl()`: base-url 이 `/chat/completions` 로 끝나면 그대로, 아니면 append → base/풀 경로 모두 허용.
- `application.yml`: `LLM_*` 우선, 없으면 `OPENROUTER_*` 폴백(기존 .env 호환). 기본 base=OpenAI, model=`gpt-4o-mini`.

### 3.4 구동 편의
- `server.port: ${SERVER_PORT:8080}`.
- `mvnw`/`mvnw.cmd`/`.mvn/` 추가 → 로컬 Maven 불필요.
- `.env.example`: 필수(Toss·Oracle)/선택(LLM·포트)/개발전용(Redmine·Gitea) 구분. README 에 clone&run + 환경변수 표.

## 4. 검증(라이브)
- `스키마 초기화 완료(멱등): 9개 DDL 블록 적용`, `뉴스 수집: fetched=250 inserted=0`(dedup), 엔드포인트 200, 테스트 33 통과, `./mvnw -v` OK.

## 5. 엣지/주의
- 테스트(H2) 프로파일은 `@Profile("default")` 로 스키마 러너 제외(기존 H2 hermetic 유지).
- 월렛은 사용자 제공(`ORACLE_TNS_ADMIN` 절대경로, 자동로그인 `cwallet.sso`).
- `app.schema.auto-init=false` 로 자동 DDL 비활성 가능(수동 운영시).

## 6. Open Questions
- (보류) Dockerfile/compose 제공.
- (보류) Flyway/Liquibase 도입 — 현재는 멱등 PL/SQL + 러너로 충분.
