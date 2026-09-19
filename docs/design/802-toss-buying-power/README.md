# 설계서: 예수금(매수가능금액) 조회 — 토스 실계좌 buying-power 연동 (#802)

> **상태**: Approved <!-- Draft | Approved | Superseded -->
> **작성**: [AI] Architect · **최종수정**: 2026-09-19
> **추적성** — Redmine: #802 · 관련 설계서: #409(`409-skeleton-toss-api`, 미해결 질문 §12 해소)
> · 구현 파일(예정): `src/main/java/com/cloudhandson/tossstock/toss/**`, `src/main/java/com/cloudhandson/tossstock/web/MarketController.java`(기존 `/api/account` 서빙 파일 — 실코드 확인 완료, 별도 AccountController 없음)
> · 테스트(예정): `src/test/java/com/cloudhandson/tossstock/toss/TossApiClientIT.java`

## 1. 목적 (Why)
토스 실계좌의 **예수금(매수가능금액)** 을 앱에서 조회할 수 있게 한다. 배경: 사용자가 "토스에 지금 자금이 없지?"라고 물었을 때, 이 앱은 시세·보유종목(수동 기록)만 다룰 뿐 실계좌 현금 잔고를 조회하는 기능이 없었음(§409 범위에서 제외, "잔고 이슈에서 규명"으로 미룸).

목표(1줄): "GET 한 번으로 실계좌 예수금(KRW/USD)을 확인할 수 있다."

## 2. 범위 (Scope)
- **포함**:
  - `TossApiClient.getBuyingPower(String currency)` — `/api/v1/buying-power` 패스스루(내부에서 `getAccounts()`로 `accountSeq` 획득).
  - 기존 `MarketController`(현재 `/api/account`를 서빙 중인 파일, 실코드 확인 완료)에 `GET /api/account/buying-power?currency=KRW|USD` 추가.
  - `X-Tossinvest-Account` 헤더값 확정 반영: **`accountSeq`**(기존 `getAccounts()` 첫 계좌의 `accountSeq` 재사용, 별도 저장 없음 — 매 호출 조회).
- **제외**:
  - 실계좌 `/api/v1/holdings`(실계좌 보유종목) 연동 — 이번 이슈는 예수금만. 필요 시 별도 이슈.
  - 주문 실행 — 여전히 범위 아님(자금 리스크).
  - 프론트엔드 UI 표시 — API만. UI는 후속 이슈(Designer 단계) 검토.

## 3. 인수조건 (Acceptance Criteria)
- [ ] `GET /api/account/buying-power?currency=KRW` → `{currency, cashBuyingPower}` 포함 200.
- [ ] `GET /api/account/buying-power?currency=USD` → 동일 스키마 200.
- [ ] `currency` 누락/잘못된 값 → 400.
- [ ] 통합 테스트: 실제 토스 API 호출로 200 + 필드 존재 확인(`.env` 키 있을 때만 실행).
- [ ] 계좌가 없을 때(accountSeq 조회 실패) → 502 또는 명확한 에러 코드(주문 경로 아니므로 자금 리스크 없음).

## 4. 컨텍스트 & 제약 — 조사 결과 (완료, 2026-09-19 실측)
- **엔드포인트**: `GET https://openapi.tossinvest.com/api/v1/buying-power?currency=KRW|USD`
- **인증**: 기존 `TossAuthClient` OAuth2 토큰 재사용 + 헤더 `X-Tossinvest-Account: {accountSeq}`.
- **§409 미해결 질문 해소**: `X-Tossinvest-Account`는 `accountNo`(`18301522197`)가 아니라 **`accountSeq`**(정수, 현재 계좌 `1`)다. 실측: `accountNo`로 호출 시 `400 account-not-found`, `accountSeq`로는 `200`.
- **`currency` 쿼리 파라미터 필수** — 누락 시 `400 invalid-request` (`field: currency`).
- **응답 스키마**(실측): `{"result":{"currency":"KRW","cashBuyingPower":"0"}}`
- **실측값**: 현재 KRW/USD 예수금 모두 `"0"` — 실계좌에 현금 없음. (참고로 `/api/v1/holdings`도 실측 `items: []`로 실계좌엔 보유종목도 없음 — 이 앱이 지금까지 다뤄온 "보유 4종목"은 앱 자체 DB의 수동 매매기록이며 실계좌와는 별개 소스임을 이번에 처음 확인.)
- **출처**: 공식 OpenAPI 문서(`https://openapi.tossinvest.com/openapi-docs/overview.md`, `openapi.json`) + 실제 API 호출 검증.

## 5. 아키텍처 개요
```
GET /api/account/buying-power?currency=KRW
  │
  ▼
[MarketController]                        ← 얇은 어댑터(I/O 경계, 기존 파일에 메서드 추가)
  │
  ▼
[TossApiClient.getBuyingPower] ──uses──► [TossApiClient.getAccounts] (accountSeq 획득)
  │                              ──uses──► [TossAuthClient] (캐시된 토큰)
  ▼
GET https://openapi.tossinvest.com/api/v1/buying-power?currency={currency}
  (Authorization: Bearer {token}, X-Tossinvest-Account: {accountSeq})
```
- 기존 `TossApiClient.getAccounts()`를 그대로 재사용해 `accountSeq`를 얻는다 — 별도 캐시/저장 불필요(계좌 목록은 이미 매 호출 조회 중이며 호출 비용 낮음).

## 6. 데이터 모델
- **TossBuyingPower**(신규 record): `currency: String`, `cashBuyingPower: String`(문자열 그대로 유지 — 금액 정밀도 손실 방지, 기존 `TossPrice` 등도 원문 유지 패턴 따름).
- **경계 검증**: `currency` 파라미터는 `KRW`/`USD`만 허용, 그 외 400.

## 7. 함수 명세 (Function Specs)

| 함수 | 책임(1줄) | 시그니처(잠정) | 입력 | 출력 | 에러/실패 | 복잡? |
|------|-----------|----------------|------|------|-----------|-------|
| `TossApiClient.getBuyingPower` | 예수금(매수가능금액) 조회 | `TossBuyingPower getBuyingPower(String currency)` | currency | TossBuyingPower | 4xx/5xx, currency 400 | 단순 |
| `MarketController.buyingPower` | 예수금 패스스루 | `List<TossBuyingPower> buyingPower(String currency)` | query | JSON | 400 검증 | 단순 |

> 두 함수 모두 기존 `getPrices`/`quote` 패턴과 동일한 단순 패스스루라 `fn-*.md` 개별 설계 불필요.

## 8. 흐름 / 알고리즘
1. 요청 진입 → 컨트롤러가 `currency` 검증(`KRW`/`USD` 아니면 400).
2. `TossApiClient.getBuyingPower(currency)` 호출.
3. 내부적으로 `getAccounts()`로 첫 계좌의 `accountSeq` 획득(계좌 없으면 예외).
4. `TossAuthClient.getAccessToken()`으로 토큰 확보(캐시 재사용).
5. `GET /api/v1/buying-power?currency={currency}` 호출, 헤더에 `Authorization`+`X-Tossinvest-Account` 첨부.
6. 응답 매핑 → 컨트롤러가 200 반환. 4xx/5xx → `TossApiException` → 기존 `@ControllerAdvice`.

## 9. 엣지케이스 & 에러 처리
- `currency` 누락/오타 → 컨트롤러 단에서 400(토스 API 호출 전에 선검증하여 불필요한 외부 호출 방지).
- 계좌 목록이 비어있음(이론상) → `TossApiException`(502류) — 자금 조회 실패를 조용히 삼키지 않음.
- 토스 API 5xx → 기존 `getPrices` 패턴과 동일하게 1회 재시도(300ms) 후 실패(신규 재시도 로직 만들지 않고 기존 `TossApiClient` 공통 처리 재사용 — 구현 시 기존 코드 구조 확인).
- **자금 리스크**: 이번에도 읽기 전용(GET)만 추가 — 주문 경로 없음.

## 10. 테스트 계획
- **단위**: `currency` 검증(허용값 외 400) — 컨트롤러 슬라이스 테스트 또는 순수 검증 함수 테스트.
- **통합(`TossApiClientIT` 확장, `.env` 키 있을 때만 `@EnabledIfEnvironmentVariable`)**:
  - `getBuyingPower("KRW")` → `cashBuyingPower` 필드 존재(값 단언 금지, 실계좌 잔고는 변동 가능).
  - `getBuyingPower("USD")` → 동일.
  - 잘못된 accountSeq 시나리오는 목(mock)으로 별도 커버(실계좌 조작 불가).
- **드라이런**: 주문 API 없음, 자금 영향 없음.

## 11. 리스크 & 대안 검토
- **`accountSeq` 하드코딩 vs 매 호출 조회**: 계좌가 여러 개일 가능성(현재는 1개) — 매번 `getAccounts()`로 첫 계좌를 쓰는 게 단순하고 안전. 다계좌 지원은 범위 밖(필요 시 후속 이슈).
- **holdings(실계좌) 동시 연동 여부**: 이번 이슈에 포함하면 범위가 커지고, 기존 "보유 종목" 개념(앱 DB 수동기록)과 혼동 우려 → 이번엔 예수금만, 실계좌 holdings는 명확히 후속 이슈로 분리.
- **UI 노출**: 이번 이슈는 API까지만. 화면 표시는 Designer 단계에서 별도 검토(홈/보유 화면 어디에 넣을지 결정 필요).

## 12. 미해결 질문 (Open Questions)
- 다계좌(복수 `accountSeq`) 지원 필요 시 계좌 선택 방식 — 현재는 단일 계좌라 보류.
- 실계좌 holdings 연동(별도 이슈) 시, 앱 DB의 수동 매매기록과 실계좌 데이터가 다르다는 점을 사용자에게 어떻게 안내할지(UX) — Designer 단계 검토 필요.
