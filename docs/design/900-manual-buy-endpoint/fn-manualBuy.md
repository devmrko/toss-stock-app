# 함수 설계서: `AutoTradeController.manualBuy` (#900)

> **부모 설계서**: ./README.md · **상태**: Draft
> **작성**: [AI] Architect · **구현**: `autotrade/AutoTradeController.java:manualBuy`
> **테스트**: `autotrade/AutoTradeControllerTest.java`

## 1. 시그니처

```java
@PostMapping("/manual-buy")
public ResponseEntity<Map<String, Object>> manualBuy(
        @RequestParam String symbol,
        @RequestParam BigDecimal budgetKrw,
        @RequestParam String confirm)
```

## 2. 책임 (단일 책임, 1줄)

안전장치를 통과한 수동 매수 1건을 `OrderExecutor.buy` 에 위임한다 — **수량은 계산하지 않는다.**

## 3. 입력

| 파라미터 | 타입 | 제약/검증 | 설명 |
|----------|------|-----------|------|
| `symbol` | String | 필수, trim 후 비어있으면 400 | 종목코드/티커 |
| `budgetKrw` | BigDecimal | `0 < x <= props.perSymbolBudget()` | 원화 예산. **수량이 아니다** |
| `confirm` | String | `"BUY-REAL"` 과 정확히 일치 | 오타·실수 호출 방지 |

**왜 수량이 아니라 예산인가**: 수량을 받으면 `OrderSizer`(#886)를 우회한다. 이 엔드포인트의
목적이 그 경로를 검증하는 것이므로, 예산을 주고 수량은 `OrderSizer` 가 계산해야 한다.

## 4. 출력

| 상태 | 조건 | 본문 |
|---|---|---|
| 200 | 주문 성공(드라이런 포함) | symbol·market·bought=true·budgetKrw·price·rationale |
| 400 | `confirm` 불일치 / 예산 범위 밖 / symbol 공백 | error |
| 409 | 서킷브레이커 / 장 마감 / 이미 보유 / 슬롯 없음 | error + 맥락 |
| 502 | `OrderExecutor.buy` 가 false (수량 0·주문 실패) | bought=false + "주문로그 참조" |
| 503 | 시세 조회 실패 | error |

**부수효과**: 성공 시 실주문 + 주문로그 + `auto_trade_position` 등록 + 알림
(전부 `OrderExecutor.buy` 안에서 일어난다).

## 5. 동작 / 알고리즘

```
1) confirm 검증
     !"BUY-REAL".equals(confirm)                  → 400 "confirm 불일치"
2) symbol 정규화
     s = symbol.trim();  s.isEmpty()              → 400
3) 예산 검증
     budgetKrw == null || <= 0                     → 400
     budgetKrw > props.perSymbolBudget()           → 400 (상한 명시)
4) 상태 게이트 (싼 것부터)
     state = stateMapper.find()
     state == null || state.isCircuitBreakerTripped() → 409
     market = BarCompleteness.marketOf(s)
     !MarketHours.isOpen(market, now)              → 409
     positionMapper.findHolding() 에 s 있으면        → 409 "이미 보유"
     props.maxSymbols() - countHolding() <= 0      → 409 "슬롯 없음"
5) 시세
     prices = priceCache.get(List.of(s))
     비었거나 lastPrice null                        → 503
     current = new BigDecimal(lastPrice)
6) 위임
     rationale = "수동 매수(#900) — #886 환율 경로 검증. 사용자 지시"
     ok = orderExecutor.buy(s, market, budgetKrw, current, rationale)
     ok ? 200 : 502
```

게이트 순서는 **되돌릴 수 없는 것을 가장 뒤로** 둔다. 입력 검증(무비용) → DB 상태 →
외부 시세(API) → 주문(되돌릴 수 없음). 서킷브레이커를 장 마감보다 먼저 보는 이유는
DB 1회 조회로 끝나고, "거래를 멈춰야 하는 상태"가 다른 모든 판단보다 우선이기 때문이다.

## 6. 에러 & 실패 모드

| 조건 | 처리 | 응답 |
|------|------|------|
| `confirm` 누락 | Spring 이 필수 파라미터 누락 처리 | 400 |
| `budgetKrw` 가 숫자 아님 | Spring 타입 변환 실패 | 400 |
| `auto_trade_state` 행 없음 | 상태를 모르면 거래하지 않는다 | 409 |
| 환율/예수금 조회 실패(US) | `OrderSizer` 가 수량 0 → `buy` false | 502 (주문로그에 사유) |
| 주가 > 환산예산 | 수량 0 → `buy` false | 502 |
| 주문 API 예외 | `OrderExecutor` 가 삼키고 false | 502 |

**예외를 자체적으로 던지지 않는다** — 모든 실패가 상태코드 + 주문로그로 드러나야 한다.

## 7. 엣지케이스

- **드라이런 상태** — `OrderExecutor.effectiveDryRun()` 이 설정/DB 중 하나라도 true 면
  실주문을 내지 않고 `dry_run=1` 포지션을 등록한다. 응답은 200/`bought=true` 다.
  **이 엔드포인트는 드라이런을 우회하지 않는다**(§9 이중 안전장치 유지).
- **KR 종목** — 같은 경로로 동작한다(환율·예수금을 읽지 않음). 금지하지 않는다.
- **예산이 1주 값보다 작음** — 수량 0 → 502. 돈은 나가지 않는다.
- **동시 호출** — 슬롯 검사와 주문 사이에 경쟁이 있을 수 있다. 수동 1회 호출 용도이므로
  락을 걸지 않는다. 최악의 경우 슬롯 초과 1건이고, `maxSymbols` 는 매수 스캔에서 다시 검사된다.
- **이미 보유 중인 종목** — 409. 평단 관리·분할매수 개념이 봇에 없으므로 추가 매수는 막는다.

## 8. 복잡도 / 성능

- 조회: `stateMapper.find()` 1회, `findHolding()` 1회, `countHolding()` 1회, `priceCache.get` 1회.
- 호출 빈도: 수동. 스케줄 없음.
