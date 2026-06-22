# 설계서: 프로젝트 뼈대 + 토스증권 Open API 연동 검증 (#409)

> **상태**: Approved <!-- Draft | Approved | Superseded -->
> **작성**: [AI] Architect · **최종수정**: 2026-06-22
> **추적성** — Redmine: #409 · 관련 ADR: ADR-0001, ADR-0002(Oracle ADB)
> · 구현 파일: `src/main/java/com/cloudhandson/tossstock/**` · 테스트: `src/test/java/com/cloudhandson/tossstock/toss/TossApiClientIT.java`

## 1. 목적 (Why)
토스증권 Open API를 연동한 주식 앱의 **뼈대(skeleton)** 를 세우고, **외부 API가 실제로 동작함을 통합 테스트로 검증**한다.
목표(1줄): "Spring Boot 앱이 토스 OAuth2 토큰을 발급받아 시세·종목·계좌를 조회할 수 있다."

## 2. 범위 (Scope)
- **포함**:
  - Spring Boot 3 (Java 17) + HikariCP + MyBatis 프로젝트 구조.
  - `.env` 기반 설정 주입 (`spring-dotenv`), 시크릿 하드코딩 금지.
  - `TossAuthClient`: OAuth2 `client_credentials` 토큰 발급 + 만료 전 캐시.
  - `TossApiClient`: 시세(`prices`)·종목(`stocks`)·계좌(`accounts`) 읽기 호출.
  - REST 컨트롤러 `/api/quote`, `/api/stock`, `/api/account` (얇은 패스스루).
  - **Oracle Autonomous DB**(mTLS 월렛) + HikariCP + MyBatis 매퍼 1개(스모크용 `watchlist`).
  - 통합 테스트(실 Toss API + 실 Oracle 호출, `.env` 자격증명 있을 때만 실행).
- **제외 (out of scope)**:
  - 주문(매수/매도) 실행 — `POST /api/v1/orders` 는 **이번 범위 아님**(리스크/돈). 다음 이슈.
  - 실시간 웹소켓/스트리밍, 인증/회원, 프론트엔드 UI.
  - 스키마 마이그레이션 도구(Flyway/Liquibase) — 다음 단계.

## 3. 인수조건 (Acceptance Criteria)
- [ ] `mvn -q -DskipTests package` 빌드 성공.
- [ ] 앱 기동 시 HikariCP 풀 + MyBatis 매퍼 정상 로드(H2).
- [ ] `TossAuthClient.getAccessToken()` 이 유효 토큰 반환(통합 테스트 200).
- [ ] `GET /api/quote?symbols=005930` → `lastPrice` 포함 JSON 200.
- [ ] `GET /api/stock?symbols=005930` → 종목명 `삼성전자` 포함 200.
- [ ] `GET /api/account` → 계좌 목록 200.
- [ ] 시크릿이 `.env` 에만 존재, git 추적 안 됨(`git check-ignore .env`).

## 4. 컨텍스트 & 제약
- **의존성**: 토스증권 Open API (`https://openapi.tossinvest.com`), H2, MyBatis, HikariCP.
- **인증**: OAuth2 `client_credentials`, HTTP Basic `base64(client_key:secret_key)` → `access_token`(`expires_in` ≈ 86399s).
- **제약**:
  - 레이트리밋(tier `BASIC`) — 토큰은 만료 전까지 **캐시 재사용**(매 호출 재발급 금지).
  - 계좌/주문 계열은 `X-Tossinvest-Account` 헤더 필요(이번엔 accounts 목록만).
  - 시세는 실시간/지연 — 검증은 200 + 필드 존재로만 판정(값 단언 금지).
- **가정**: `.env` 의 `TOSS_CLIENT_KEY/TOSS_SECRET_KEY` 가 유효한 live 키.

## 5. 아키텍처 개요
```
HTTP 요청
  │
  ▼
[QuoteController] [StockController] [AccountController]   ← 얇은 어댑터(I/O 경계)
  │                    │                    │
  └─────────┬──────────┴──────────┬─────────┘
            ▼                      ▼
      [TossApiClient] ───uses──► [TossAuthClient] ──(캐시된 토큰)──► POST /oauth2/token
            │  (RestClient)
            ▼
   https://openapi.tossinvest.com/api/v1/{prices|stocks|accounts}

[WatchlistMapper] ──MyBatis──► HikariCP ──► H2 (스모크/헬스)
```
- **I/O ↔ 순수 로직 경계**: HTTP/DB I/O 는 `*Client`/`*Mapper` 에 격리. 토큰 만료 판정(`isExpired`)은 순수 함수로 단위 테스트 가능.

## 6. 데이터 모델
- **TossToken** (메모리 캐시): `accessToken: String`, `tokenType: String`, `expiresAtEpochSec: long`.
- **TossPrice**(응답 매핑): `symbol`, `lastPrice`, `currency`, `timestamp`.
- **TossStock**: `symbol`, `name`, `market`, `isinCode`, `currency`, `status`.
- **TossAccount**: `accountNo`, `accountSeq`, `accountType`.
- **Watchlist**(Oracle): `id NUMBER IDENTITY PK`, `symbol VARCHAR2(20)`, `memo VARCHAR2(200)`, `created_at TIMESTAMP`. DDL: `db/watchlist.sql`(멱등). 테스트는 H2(MODE=Oracle).
- **경계 검증**: `symbols` 파라미터 공백/길이 검증, 누락 시 400.

## 7. 함수 명세 (Function Specs)

| 함수 | 책임(1줄) | 시그니처(잠정) | 입력 | 출력 | 에러/실패 | 복잡? |
|------|-----------|----------------|------|------|-----------|-------|
| `TossAuthClient.getAccessToken` | 유효 토큰 반환(캐시 우선, 만료시 재발급) | `String getAccessToken()` | - | access token | 인증실패→`TossApiException` | **복잡** |
| `TossAuthClient.requestToken` | `/oauth2/token` 호출 | `TossToken requestToken()` | client/secret | TossToken | 401/네트워크 | **복잡** |
| `TossToken.isExpired` | 만료(60s 여유) 여부 | `boolean isExpired(long nowSec)` | now | bool | - | 단순 |
| `TossApiClient.getPrices` | 시세 조회 | `List<TossPrice> getPrices(List<String> symbols)` | symbols | 시세 | 4xx/5xx | 단순 |
| `TossApiClient.getStocks` | 종목정보 조회 | `List<TossStock> getStocks(List<String> symbols)` | symbols | 종목 | 4xx/5xx | 단순 |
| `TossApiClient.getAccounts` | 계좌 목록 조회 | `List<TossAccount> getAccounts()` | - | 계좌 | 4xx/5xx | 단순 |
| `QuoteController.quote` | 시세 패스스루 | `ResponseEntity quote(String symbols)` | query | JSON | 400 검증 | 단순 |
| `WatchlistMapper.findAll` | 관심종목 조회(스모크) | `List<Watchlist> findAll()` | - | rows | SQL | 단순 |

> 복잡 함수 `getAccessToken`/`requestToken` → `fn-toss-auth.md` 개별 설계.

## 8. 흐름 / 알고리즘
1. 요청 진입 → 컨트롤러가 `symbols` 검증.
2. `TossApiClient` 가 `TossAuthClient.getAccessToken()` 호출.
3. 토큰 캐시 유효 → 그대로, 만료/없음 → `requestToken()` 으로 재발급 후 캐시.
4. `Authorization: Bearer {token}` 로 `/api/v1/...` GET.
5. 응답 JSON 매핑 → 컨트롤러가 200 반환. 4xx/5xx → `TossApiException` → `@ControllerAdvice` 가 502/적절 코드.

## 9. 엣지케이스 & 에러 처리
- 토큰 발급 401 → 즉시 실패(설정 오류), 재시도 금지.
- 시세 API 일시 5xx → 1회 재시도(백오프 300ms) 후 실패.
- `symbols` 누락/공백 → 400 `invalid-request`.
- **안전한 기본값**: 어떤 실패도 **주문 경로 없음**(이번 범위) → 자금 리스크 0. 읽기 전용.
- 동시성: 토큰 캐시 갱신은 `synchronized`(이중 발급 방지).

## 10. 테스트 계획
- **단위**: `TossToken.isExpired` (경계: now == exp-60).
- **통합(`TossApiClientIT`, `.env` 키 존재 시만 `@EnabledIfEnvironmentVariable`)**:
  - 토큰 발급 200, `getPrices(["005930"])` → lastPrice 존재.
  - `getStocks(["005930"])` → name == "삼성전자".
  - `getAccounts()` → 비어있지 않음.
- **스모크**: 컨텍스트 로드 + `WatchlistMapper.findAll()` (H2 schema.sql).
- **드라이런**: 주문 API 미구현이므로 자금 영향 없음.

## 11. 리스크 & 대안 검토
- **라이브 키 사용**: 읽기 전용 엔드포인트만 호출 → 리스크 낮음. 그래도 키는 `.env` 격리 + 로테이션 권고.
- HTTP 클라이언트: `RestClient`(Spring 6.1+) 채택 vs WebClient(리액티브 불필요)/RestTemplate(레거시). → ADR-0001.
- 토큰 저장: 메모리 캐시 vs DB/Redis. 단일 인스턴스 뼈대이므로 메모리. 멀티 인스턴스 시 재검토.

## 12. 미해결 질문 (Open Questions)
- `X-Tossinvest-Account` 헤더 값: `accountNo`(`18301522197`) vs `accountSeq` — holdings 호출이 `account-not-found`. 주문/잔고 이슈에서 규명.
- 주문 API 멱등성/체결 콜백 — 다음 이슈 범위.
- 레이트리밋 정확 수치(분당 호출 한도) — 운영 전 확인 필요.
