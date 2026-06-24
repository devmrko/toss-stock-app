# toss-stock-app

토스증권 **Open API** 연동 주식 앱. Spring Boot 3 + HikariCP + MyBatis.
현재 단계: **뼈대(skeleton) + 읽기 API 연동 검증** (Redmine #409).

## 스택
- Java 21, Spring Boot 3.3.5
- HTTP: `RestClient` (ADR-0001)
- DB: **Oracle Autonomous DB 26ai** (mTLS SSO 월렛, `ojdbc11`) + MyBatis + HikariCP (ADR-0002)
  - 테스트는 H2(MODE=Oracle) hermetic / 실 Oracle 검증은 `OracleWatchlistIT`
- 설정/비밀: `.env` (spring-dotenv) — **하드코딩·커밋 금지**

## 토스 Open API (검증 완료)
| 용도 | 호출 |
|------|------|
| 토큰 | `POST /oauth2/token` (Basic `base64(clientKey:secretKey)`, `grant_type=client_credentials`) |
| 시세 | `GET /api/v1/prices?symbols=005930` |
| 종목 | `GET /api/v1/stocks?symbols=005930` |
| 계좌 | `GET /api/v1/accounts` |

> 주문(`POST /api/v1/orders`)은 **이번 범위 아님**(리스크). 읽기 전용.

## 빠른 시작 (clone & run)
> 준비물: **Java 21**, **토스증권 Open API 키**, **Oracle DB(Autonomous + mTLS 월렛)**. (Maven 은 동봉된 `./mvnw` 사용)

```bash
git clone <repo-url> && cd toss-stock-app
cp .env.example .env          # 값 채우기 (아래 표 참고)
set -a; . ./.env; set +a      # .env → 환경변수
./mvnw spring-boot:run        # http://localhost:8080  (포트 변경: SERVER_PORT=8081)
```
- **테이블은 기동 시 자동 생성**(`SchemaInitializer`, 멱등) — 별도 DDL 실행 불필요.
- **유니버스(KRX 상장목록)도 자동 시드**, **뉴스는 LLM 키가 있으면 기동 직후 자동 수집**.
- 빌드 산출물(실행 jar): `./mvnw -DskipTests package` → `java -jar target/toss-stock-app-*.jar`

### 환경변수
| 구분 | 키 | 설명 |
|---|---|---|
| **필수** | `TOSS_CLIENT_KEY` / `TOSS_SECRET_KEY` | 토스증권 Open API |
| **필수** | `ORACLE_TNS_ADMIN` | OCI 월렛 폴더 절대경로(`cwallet.sso` 포함) |
| **필수** | `ORACLE_SERVICE` / `ORACLE_USER` / `ORACLE_PASSWORD` | 서비스명(`xxxx_tp`)·계정 |
| 선택 | `LLM_BASE_URL` / `LLM_API_KEY` / `LLM_MODEL` | **뉴스 분류용 OpenAI 호환 LLM**. 없으면 핵심기능만 동작 |
| 선택 | `SERVER_PORT` | 기본 8080 |

> **뉴스 LLM**은 OpenAI 호환이면 무엇이든 가능 — OpenAI(`https://api.openai.com/v1`, `gpt-4o-mini`), OpenRouter(`https://openrouter.ai/api/v1`, `anthropic/claude-haiku-4.5`), 로컬 ollama/vLLM 등. `LLM_BASE_URL` 은 base 또는 풀 `/chat/completions` 경로 모두 허용. (기존 `OPENROUTER_*` 도 폴백 지원)

### 웹페이지
- **`GET /watchlist.html`** — 워치리스트 거래량 모니터링 (5초 폴링, 거래량 내림차순, 상위 50). 설계: `docs/design/414-watchlist-page/`
- **`GET /top50.html`** — 시장 전체(KRX KOSPI+KOSDAQ ≈2,605) **오늘 거래량 탑50** (일배치 스캔). 설계: `docs/design/416-market-top50/`
  - 갱신: `POST /api/top50/refresh`(백그라운드 ~5분) · 조회: `GET /api/top50` · 자동: 영업일 15:40 KST
  - 유니버스 시드: `resources/universe-krx.json`(KRX 상장목록) → Oracle `UNIVERSE`. DDL `db/top50.sql`.
  - ⚠️ 포트 8080이 다른 앱에 점유 중이면 `SERVER_PORT=8081 mvn spring-boot:run` 로 변경.

### 앱 엔드포인트
- `GET /api/quote?symbols=005930` — 시세
- `GET /api/stock?symbols=005930` — 종목정보
- `GET /api/account` — 계좌목록
- `GET /api/watchlist/quotes` — 워치리스트 거래량 정렬 모니터링(이름·등락률·거래량)
- `POST /api/watchlist {"symbol","memo"}` · `DELETE /api/watchlist/{id}` — 종목 추가/삭제
- `GET /api/watchlist` — 원본 행(스모크)

## 테스트
```bash
mvn test                             # 단위 + 컨텍스트/MyBatis 스모크
set -a; . ./.env; set +a; mvn verify # + 실제 토스 API 통합 테스트(*IT)
```
키가 없으면 `*IT` 는 자동 skip(`@EnabledIfEnvironmentVariable`).

## 문서 (cloud-handson 컨벤션)
- 설계서: `docs/design/409-skeleton-toss-api/`
- ADR: `docs/adr/0001-restclient-and-memory-token-cache.md`
- 표준/파이프라인: `CLAUDE.md`, `docs/README.md`
- 추적성: Redmine #409 ↔ 설계서 ↔ git 커밋
