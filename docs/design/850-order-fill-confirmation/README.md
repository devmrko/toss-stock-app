# 설계서: 주문 체결가 확정 대기(polling) 추가 (#850)

> **상태**: Approved
> **작성**: [AI] Architect · **최종수정**: 2026-10-08
> **추적성** — Redmine: #850 · 관련 ADR: 없음
> · 구현 파일: `OrderExecutor.java`, `RangeOrderExecutor.java`(수정) ·
>   테스트: `OrderExecutorTest`, `RangeOrderExecutorTest`(확장), `fn-wait-for-fill.md` 참조

## 1. 목적 (Why)
실측(2026-10-08): 259630(엠플러스) 45회 왕복 전체를 토스 `GET /api/v1/orders?status=CLOSED`
(실제 commission/tax/averageFilledPrice를 정상 반환하는 리스트 엔드포인트, 이번에 처음 확인됨
— 기존에 쓰던 `GET /api/v1/orders/{orderId}` 단건 조회는 commission/tax가 항상 "0"이라 #847이
계산식으로 대체했던 바로 그 엔드포인트와는 다름)로 재계산한 순손익이 -154,463원으로, 사용자가
말한 실제 토스 앱 숫자와 **원 단위까지 정확히 일치**했다. 반면 DB 기록(가격차만)은 -24,950원.

90건(45왕복×2) 중 69건(77%)에서 DB에 저장된 "체결가"가 토스의 실제 평균체결가와 달랐고,
방향이 전부 우리한테 유리한 쪽(매수는 더 싸게, 매도는 더 비싸게 기록)이었다 — 가격차손익만으로
-68,190원의 착시(합계 -93,140원이 실제, -24,950원이 기록값). #847(수수료 미기록)과는 별개의,
새로 발견된 버그.

## 2. 범위 (Scope)
- **포함**: `OrderExecutor.buy/sell`, `RangeOrderExecutor.buy/sell` — `placeOrder()` 직후
  받은 응답의 `status`가 아직 확정(FILLED 등 종결 상태)이 아니면 `GET /orders/{orderId}`를
  짧은 간격으로 재조회해 확정 상태를 기다린 뒤 그 시점의 `averageFilledPrice`를 사용.
- **제외 (out of scope)**: `GET /orders?status=CLOSED` 리스트 엔드포인트를 이용한 사후
  대사(reconciliation) 배치 — 과거 데이터 백필은 별도 이슈로 분리(§12). 주문 취소/재시도
  로직 변경 — 이 이슈는 "기록되는 가격의 정확도"만 다룬다.

## 3. 인수조건 (Acceptance Criteria)
- [ ] `placeOrder()` 응답의 `status`가 `FILLED`가 아니고 아직 진행 중("PENDING",
      "PARTIAL_FILLED", "PENDING_CANCEL", "PENDING_REPLACE")이면, 최대 4회·300ms 간격으로
      `GET /orders/{orderId}`를 재조회해 상태가 바뀔 때까지(또는 재시도 소진까지) 기다린다.
- [ ] 재조회 끝에 얻은 최신 주문 정보의 `averageFilledPrice`를 체결가로 사용한다(기존
      `actualFilledPrice` 폴백 체인은 유지 — 끝까지 null이면 견적가 폴백).
- [ ] 재조회 자체가 실패(예외)해도 거래 흐름은 중단되지 않고 가진 값으로 계속 진행한다
      (새 리스크 추가 금지 — 재시도는 어디까지나 정확도 개선, 주문 성공/실패 판정에 영향 없음).
- [ ] 기존 테스트 전부 통과 + 신규: "즉시 FILLED면 재조회 안 함", "PENDING→FILLED로
      바뀌면 재조회 결과 사용", "재시도 소진 후에도 미확정이면 마지막 값 사용", "재조회
      예외가 거래를 막지 않음".

## 4. 컨텍스트 & 제약
- 의존성: `TossApiClient.getOrder(orderId)`(기존 메서드, #843 `withAuthRetry` 적용됨 — 재사용).
- 제약: 레이트리밋(`ORDER` 그룹) — 재조회는 주문 1건당 최대 4회로 제한(무제한 폴링 금지).
  지연시간: 최악의 경우 주문 1건당 약 1.2초 추가(300ms×4) — 매수/매도는 틱당 드물게
  발생하므로(슬롯 한정) 스케줄러 전체 지연에 미치는 영향은 미미.
- 가정: 시장가 주문은 결국 FILLED 또는 거부/취소로 종결된다 — `PARTIAL_FILLED`가 최종
  상태(더 안 채워짐)인 경우도 있으나(§9), 이 설계는 "계속 진행 중인지"만 보고 재시도하므로
  최종 PARTIAL_FILLED도 재시도 소진 후 그 시점 값을 그대로 쓴다(에러 아님).

## 5. 아키텍처 개요
- I/O ↔ 순수 로직 경계: `isStillInFlight(TossOrder)`는 순수 판별 함수(상태 문자열 매칭).
  `waitForFill(TossOrder, Supplier<TossOrder> refetch)`은 I/O(재조회)를 수행하는 경계 함수 —
  복잡(분기+재시도+외부 I/O) → `fn-wait-for-fill.md` 작성 대상.
```
placeOrder() 응답(order)
   │
   ▼
isStillInFlight(order.status())? ──No──▶ order 그대로 사용
   │Yes
   ▼
최대 4회: sleep(300ms) → toss.getOrder(orderId) → order 갱신
   │(상태가 바뀌면 루프 탈출)
   ▼
actualFilledPrice(최신 order, fallback=견적가)
```

## 6. 데이터 모델
변경 없음(DB 스키마 영향 없음 — 저장되는 `entry_price`/`requested_price`의 **값의 정확도**만
개선).

## 7. 함수 명세 (Function Specs)

| 함수 | 책임 | 시그니처(잠정) | 복잡? |
|------|------|----------------|-------|
| `isStillInFlight` | 주문 상태가 아직 진행 중인지 판별 | `static boolean isStillInFlight(String status)` | 단순 |
| `waitForFill` | 체결 확정까지 짧게 재조회 | `TossOrder waitForFill(TossOrder initial)` | **복잡**(외부 I/O·재시도·리스크 경로) → `fn-wait-for-fill.md` |
| `OrderExecutor.buy/sell`(수정) | `placeOrder()` 후 `waitForFill` 경유 | 시그니처 불변 | 단순 |
| `RangeOrderExecutor.buy/sell`(수정) | 동일 | 시그니처 불변 | 단순 |

## 8. 흐름 / 알고리즘
`fn-wait-for-fill.md` 참조.

## 9. 엣지케이스 & 에러 처리
- 재조회 자체가 예외(네트워크/401 등) → 즉시 루프 중단, 가진 값(직전 order)으로 계속 진행
  (§3 인수조건 — 재시도 실패가 거래를 막으면 안 됨, #808의 기존 원칙과 동일).
- 재시도 4회 소진 후에도 PENDING/PARTIAL_FILLED → 에러 아님, 그 시점 값 사용(§4 가정).
- `getOrder` 응답이 `null` → `waitForFill`은 직전 order를 그대로 반환(갱신 안 함).

## 10. 테스트 계획
`fn-wait-for-fill.md` §테스트 참조. `OrderExecutorTest`/`RangeOrderExecutorTest`에 4케이스
추가(§3). `Thread.sleep` 때문에 테스트에서 재시도 횟수를 최소화할 수 있도록 private 상수를
그대로 두되, 테스트는 "즉시 FILLED" 경로(재조회 0회, 빠름) 위주로 작성하고 재시도 소진
경로는 1회 재조회만 발생하도록(예: 2번째 조회에서 FILLED) 설계해 테스트 시간을 줄인다.

## 11. 리스크 & 대안 검토
- 대안(기각): `GET /orders?status=CLOSED` 리스트 엔드포인트로 사후 대사 — commission/tax까지
  정확하게 얻을 수 있어 더 이상적이지만, 체결 직후 언제 데이터가 채워지는지(실시간성) 확인 안
  됨 + 호출 1건당 전체 종목 스캔이 필요해 핫패스에 넣기엔 무겁다. 이번엔 "주문 직후 짧게
  기다렸다 단건 재조회"만으로 범위를 좁히고, 리스트 엔드포인트 기반 사후 대사는 별도 이슈로
  분리(§12).
- 리스크: 낮음 — 재시도 실패해도 기존 폴백(견적가)으로 수렴, 신규 실패모드 없음. 지연시간
  증가는 유계(최대 1.2초/주문)이고 측정된 리스크(주문 정확도) 대비 작다.

## 12. 미해결 질문 (Open Questions)
- 과거(이미 저장된) 체결가 오류 데이터를 `GET /orders?status=CLOSED`로 소급 보정(백필)할지 —
  별도 이슈로 분리 제안.
- `PARTIAL_FILLED`가 영구 종결 상태인 경우(시장가 주문인데도 일부만 체결되고 남은 수량이
  취소되는 경우) 포지션 수량(`entry_qty`)을 실제 체결수량(`filledQuantity`)과 다르게 저장하는
  기존 동작도 재검토 필요할 수 있음(이번 이슈 범위 밖, 별도 플래그).
