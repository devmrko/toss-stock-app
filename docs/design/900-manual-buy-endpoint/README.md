# 설계서: 수동 매수 엔드포인트 (#900)

> **상태**: Draft
> **작성**: [AI] Architect · **최종수정**: 2026-10-10
> **추적성** — Redmine: #900 · 선행: #886 · 관련 ADR: 없음
> · 구현 파일: `autotrade/AutoTradeController.java`
> · 테스트: `autotrade/AutoTradeControllerTest.java`

## 1. 목적 (Why)

> #886 설계서 §12-3 의 미결을 실주문 1건으로 해소한다 — **통합증거금 동작을 코드가 모른다.**

#886 은 US 매수 수량을 `min(원화예산 ÷ 환율, USD 예수금) ÷ 달러주가` 로 고쳤다. 최솟값
전략이라 통합증거금 체계든 USD 전용이든 안전하지만, **실제로 KRW 로 US 주식을 살 수 있는
계좌인지는 확인하지 않았다.** 단위 테스트로는 알 수 없다 — 브로커 응답이 필요하다.

사용자 지시: "1주 실주문으로 체결 경로 확인해봐".

## 2. 범위 (Scope)

- **포함**: `OrderExecutor.buy` 를 호출하는 운영용 엔드포인트 + 안전장치.
- **제외**
  - **수량 직접 지정** — 수량을 받으면 `OrderSizer`(#886)를 우회해 검증 목적이 사라진다.
    예산을 받아 `OrderSizer` 가 수량을 계산하게 한다.
  - 자동 반복·예약 — 사용자 지시 시 1회만. (사용자 선호: 무인 예약 안 함)
  - 매도 — 이미 `POST /positions/{symbol}/sell` 이 있다.
  - US 일봉 정기 갱신(#901) — 주문은 실시간 시세를 쓰므로 무관하다.

## 3. 인수조건 (Acceptance Criteria)

- [ ] **AC1** `confirm` 이 정확한 문자열이 아니면 400, 주문이 나가지 않는다.
- [ ] **AC2** `budgetKrw` 가 `per-symbol-budget` 초과면 400.
- [ ] **AC3** `budgetKrw` 가 0 이하거나 숫자가 아니면 400.
- [ ] **AC4** 해당 시장이 마감 중이면 409.
- [ ] **AC5** 이미 보유 중인 종목이면 409.
- [ ] **AC6** 열린 슬롯이 없으면 409.
- [ ] **AC7** 서킷브레이커 발동 상태면 409.
- [ ] **AC8** 시세 조회 실패면 503.
- [ ] **AC9** 정상 호출이면 `OrderExecutor.buy` 가 호출된다(수량은 엔드포인트가 지정하지 않는다).
- [ ] **AC10** 주문 실패면 502, 성공이면 200.

## 4. 컨텍스트 & 제약

- **왜 원시 Toss API 가 아닌가**: 직접 호출하면 브로커 경로만 검증되고 `OrderSizer`·주문로그·
  `auto_trade_position` 등록·손절 감시 연결이 전부 빠진다. 더 나쁘게는 원장에 없는 포지션이
  생겨 **봇이 손절을 걸지 않는다**. `sellPosition` 이 같은 이유로 `OrderExecutor.sell` 을
  쓰도록 설계돼 있다(그 javadoc 참조) — 같은 원칙을 따른다.
- **왜 스케줄러 경로를 못 쓰는가**: `orderExecutor.buy` 호출부는 `AutoTradeScheduler:425`
  하나뿐이고 후보 등록 + 전 게이트 통과를 요구한다. 1주만 사는 통제된 테스트에 쓸 수 없다.
- **시장 판정**: `BarCompleteness.marketOf(symbol)` 재사용(6자리 숫자 → KR, 그 외 → US).
- **드라이런**: 우회하지 않는다. `OrderExecutor.effectiveDryRun()` 이 설정/DB 상태를 그대로
  본다 — 둘 중 하나라도 드라이런이면 실주문이 나가지 않는다(§9 이중 안전장치 유지).
  실주문 검증에는 양쪽이 false 여야 하고, 현재 운영값은 `AUTO_TRADE_DRY_RUN=false`,
  `auto_trade_state.dry_run=0` 이다.
- **예산 상한**: `OrderExecutor.buy` 가 이미 `per-symbol-budget` 초과를 막는다. 엔드포인트에서
  먼저 막는 이유는 경계 검증을 호출자에게 400 으로 알려주기 위함이다(원칙 §1 "외부 입력은
  경계에서 검증").

## 5. 아키텍처 개요

```
POST /api/autotrade/manual-buy?symbol=&budgetKrw=&confirm=
     │
     ├─ confirm 불일치            → 400
     ├─ budgetKrw 범위 밖          → 400
     ├─ 서킷브레이커                → 409
     ├─ 장 마감(MarketHours)       → 409
     ├─ 이미 보유                   → 409
     ├─ 슬롯 없음                   → 409
     ├─ priceCache 실패            → 503
     │
     └─ OrderExecutor.buy(symbol, market, budgetKrw, current, rationale)
            └─ OrderSizer.buyQuantity(...)   ← #886 검증 대상
            └─ FxRateCache.usdKrw()          ← #886 검증 대상
            └─ toss.getBuyingPower("USD")    ← #886 검증 대상
            └─ toss.placeOrder(...)          ← 브로커 경로 검증 대상
            └─ 주문로그 + auto_trade_position 등록 + 알림
```

게이트 순서는 **싼 것·되돌릴 수 없는 것 먼저**다 — 입력 검증(무비용) → 상태 확인(DB) →
시세 조회(API) → 주문(되돌릴 수 없음).

## 6. 데이터 모델

### 요청

| 파라미터 | 타입 | 검증 |
|---|---|---|
| `symbol` | String | 필수, 공백 불가 |
| `budgetKrw` | BigDecimal | 필수, `0 < x <= per-symbol-budget` |
| `confirm` | String | 필수, `"BUY-REAL"` 과 정확히 일치 |

### 응답 (200)

| 필드 | 의미 |
|---|---|
| `symbol` / `market` | |
| `bought` | boolean |
| `budgetKrw` | 요청 예산 |
| `price` | 주문 시점 현재가 |
| `rationale` | 주문로그에 남은 근거(수량·환율·환산액 포함 — `OrderSizer.note`) |

스키마 변경 **없음**.

## 7. 함수 명세

| 함수 | 책임(1줄) | 시그니처 | 입력 | 출력 | 에러/실패 | 복잡? |
|------|-----------|----------|------|------|-----------|-------|
| `AutoTradeController.manualBuy` | 안전장치 통과 후 `OrderExecutor.buy` 위임 | `ResponseEntity<Map<String,Object>> manualBuy(String symbol, BigDecimal budgetKrw, String confirm)` | §6 | 200/400/409/502/503 | 각 단계에서 조기 반환 | **복잡**(분기 7개·리스크 경로) → `fn-manualBuy.md` |

## 8. 흐름 / 알고리즘

`fn-manualBuy.md` 참조.

## 9. 엣지케이스 & 에러 처리

| 상황 | 처리 |
|---|---|
| `confirm` 누락 | 400 (Spring 이 필수 파라미터 누락을 400 으로 처리) |
| `confirm` 오타 | 400, 주문 없음 |
| `budgetKrw` 음수·0 | 400 |
| `budgetKrw` > 600,000 | 400 |
| 숫자 아님 | 400 (Spring 타입 변환 실패) |
| 종목이 US 인데 환율 조회 실패 | `OrderExecutor` 가 수량 0 → `bought=false` → **502**. 주문로그에 "환율 조회 실패" |
| 종목이 US 인데 예수금 부족 | 수량 0 → 502. 주문로그에 "상한=예수금" |
| 주가 > 환산예산 | 수량 0 → 502(1주도 못 산다 — #886 §12-2) |
| 드라이런 상태 | 주문은 나가지 않고 포지션이 `dry_run=1` 로 등록된다. 응답 `bought=true` |
| 장 마감 | 409 — 시장가 주문을 닫힌 시장에 넣으면 체결가를 통제할 수 없다(`sellPosition` 과 같은 판단) |

## 10. 테스트 계획

### `AutoTradeControllerTest` 추가

| 케이스 | 매핑 |
|---|---|
| `confirm` 불일치 → 400, `orderExecutor.buy` 미호출 | AC1 |
| 예산 초과 → 400 | AC2 |
| 예산 0·음수 → 400 | AC3 |
| 장 마감 → 409 | AC4 |
| 이미 보유 → 409 | AC5 |
| 슬롯 없음 → 409 | AC6 |
| 서킷브레이커 → 409 | AC7 |
| 시세 실패 → 503 | AC8 |
| 정상 → `buy(symbol, market, budgetKrw, price, ...)` 호출, 200 | AC9 |
| `buy` 가 false → 502 | AC10 |
| US 종목이면 market="US" 로 넘어간다 | AC9 |

## 11. 실행 계획 (실주문)

- **미국장 개장: 2026-10-12(월) 22:30 KST.** 2026-10-10 은 토요일로 마감이다.
- 종목 후보: CCL $26.13(최저가, 1주 약 35,000원) 또는 SMCI $42.77(약 57,000원).
- 호출 예: `POST /api/autotrade/manual-buy?symbol=CCL&budgetKrw=36000&confirm=BUY-REAL`
- **사용자 지시가 있을 때만 실행한다** — 무인 예약하지 않는다.
- 체결 후 확인 항목
  1. 통합증거금 여부 — KRW 예수금이 줄었는지 vs USD 예수금이 줄었는지
  2. 체결가·수수료(US 0.001)
  3. `auto_trade_position` 등록 및 `peak_price` 초기값
  4. 다음 틱에서 손절선(하드 −10% / 추적 −10%)이 계산되는지
  5. 주문로그 `message` 에 환율·환산액·수량이 남았는지

## 12. 한계 / 미결 (원칙 §13)

1. **실거래 엔드포인트를 늘리는 것 자체가 위험이다.** `confirm` + 예산 상한 + 상태 게이트로
   막았지만, 잘못 호출하면 실제로 돈이 나간다. 그래서 수량을 받지 않고(예산만) 상한을
   슬롯예산으로 묶었다 — 최악의 경우 손실이 슬롯 1개분으로 제한된다.
2. **1건 검증의 한계.** 통합증거금 동작은 1건으로 확인되지만, 체결 품질(슬리피지)·
   부분체결·시간외 동작은 알 수 없다.
3. **테스트로 산 포지션은 봇이 관리한다.** 손절선이 걸리므로 방치해도 −10% 에서 정리된다.
   다만 "검증용으로 샀을 뿐인 포지션"이 슬롯 1개를 점유한다 — 확인 후 수동 매도할지는
   사용자 판단이다.
