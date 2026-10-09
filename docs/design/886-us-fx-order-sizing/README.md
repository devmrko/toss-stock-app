# 설계서: US 매수 수량에 환율·예수금 반영 (#886)

> **상태**: Draft
> **작성**: [AI] Architect · **최종수정**: 2026-10-09
> **추적성** — Redmine: #886 · 관련 ADR: 없음
> · 구현 파일: `autotrade/FxRateCache.java`, `autotrade/OrderSizer.java`, `autotrade/OrderExecutor.java`
> · 테스트: `autotrade/OrderSizerTest.java`, `autotrade/FxRateCacheTest.java`, `autotrade/OrderExecutorTest.java`

## 1. 목적 (Why)

> 원화 슬롯예산을 달러 주가로 그대로 나눠서 **60만원 주문이 8억원으로 계산된다.**

```java
// OrderExecutor:68 — 현재 코드
BigDecimal qty = budget.divide(currentPrice, 0, RoundingMode.DOWN);
```

`budget` = 600,000(원), `currentPrice` = 456.35(달러) → **1,315주 ≈ $600,000 ≈ 8억원**.
실제 USD 예수금은 **$1,500** 이다 — 400배다.

**환율 변환 코드가 코드베이스 전체에 없다**(`grep -riE "exchange|환율|fx"` 0건).
`TossApiClient.getBuyingPower` 는 구현돼 있으나 **매수 경로에서 호출되지 않는다.**

지금까지 사고가 안 난 이유는 #885(US 최신 일봉이 미완성 바)가 인기 게이트에서 US 를 전부
막아 **US 주문로그가 0행**이었기 때문이다. #885 를 고치면 이 결함이 즉시 실현된다 —
**#886 이 #885 보다 먼저, 또는 함께 배포되어야 한다.**

## 2. 범위 (Scope)

- **포함**
  - USD/KRW 환율 조회 + 캐시(`FxRateCache`)
  - 주문 수량 계산을 순수 함수로 분리(`OrderSizer`)하고 US 는 환율·USD 예수금 반영
  - 환율/예수금 조회 실패 시 **US 매수 포기**(fail-closed)
- **제외 (out of scope)**
  - **슬롯 예산 체계 변경** — §4 의 가정대로 "슬롯당 60만원"의 원화 의미를 유지한다.
    총예산(3,000,000원)·슬롯수(5)를 바꾸지 않는다.
  - 매도 수량 — 매도는 보유 수량 전량이라 환율이 개입하지 않는다.
  - 환전 주문(KRW→USD) 자동화 — 예수금 범위 안에서만 산다.
  - US 매수 활성화(`screener-markets`) — #885 와 함께 별도 판단.

## 3. 인수조건 (Acceptance Criteria)

- [ ] **AC1** US 매수 수량이 `min(원화예산÷환율, USD예수금) ÷ 달러주가` 로 계산된다.
- [ ] **AC2** 환율 조회 실패 시 US 매수를 하지 않는다(주문로그에 사유 기록).
- [ ] **AC3** USD 예수금 조회 실패 시 US 매수를 하지 않는다.
- [ ] **AC4** USD 예수금이 원화예산 환산액보다 작으면 **예수금이 상한**이 된다.
- [ ] **AC5** 수량 0(예산·예수금 부족)이면 주문하지 않고 사유를 기록한다.
- [ ] **AC6** KR 매수 수량 계산은 **변하지 않는다**(회귀 금지).
- [ ] **AC7** 환율은 캐시되어 틱(1분)마다 외부 호출을 하지 않는다.
- [ ] **AC8** 주문로그·알림에 적용 환율과 달러 환산액이 남는다.

## 4. 컨텍스트 & 제약

- **가정(사용자 결정 불필요)**: "슬롯당 60만원"은 **원화 기준 리스크**다. US 슬롯도 같은
  원화 리스크를 지도록 환율로 환산한다 → 슬롯당 약 **$447**(환율 1,340.78 기준).
  총예산·슬롯수·종목당 리스크가 그대로 유지되므로 사용자 리스크 노출이 변하지 않는다.
  (대안이었던 "별도 USD 슬롯예산"은 총 노출을 늘리므로 채택하지 않았다.)
- **USD 예수금 상한을 함께 거는 이유**: 계좌에 달러가 $1,500 뿐이므로, 환산액만 보고
  주문하면 통합증거금 여부에 따라 주문 거부나 의도치 않은 환전이 날 수 있다.
  **두 값의 최솟값**을 쓰면 어느 쪽 체계에서도 안전하다.
- **환율 소스**: Toss API 에 환율 엔드포인트가 없다(코드·문서 확인). 야후
  `KRW=X`(`v8/finance/chart`)를 쓴다 — 앱이 이미 같은 엔드포인트로 지수 수익률을 받고 있어
  새 의존성이 아니다. 실측 1,340.78(최근 5일 1,339.1~1,343.8로 안정).
- **레이트리밋**: 환율은 틱마다 필요하므로 캐시 필수. `ValuationClient` 의 `Cached<T>` 패턴을
  따른다(성공 TTL / 실패 TTL 분리).
- **`budget_allocated` 컬럼**: 저장만 되고 **어떤 판정에도 읽히지 않는다**(전수 확인).
  원화 배정액(슬롯예산) 의미를 유지한다.
- **HTTP 429 전례**: `getBuyingPower` 는 Toss API 이고 이미 429 를 맞은 적이 있다
  (오늘 KRW 조회에서 실제로 발생). 매수 직전 1회만 호출하고, 실패는 매수 포기로 처리한다.

## 5. 아키텍처 개요

```
                     ┌──────────── I/O ────────────┐
야후 KRW=X ─────────▶│ FxRateCache.usdKrw()        │  성공 TTL 1h / 실패 TTL 1m
                     │   → BigDecimal 또는 null    │
Toss buying-power ──▶│ TossApiClient.getBuyingPower│  매수 직전 1회
                     │   → TossBuyingPower 또는 예외│
                     └──────────────┬──────────────┘
                                    ▼
                     ┌──────── 순수 로직 ─────────┐
                     │ OrderSizer.buyQuantity(    │
                     │   market, budgetKrw,        │
                     │   price, fxRate, cashUsd)   │
                     │  → Sizing(qty, note)        │
                     └──────────────┬──────────────┘
                                    ▼
                          OrderExecutor.buy(...)
                            qty <= 0 → 주문 안 함 + 사유 기록
```

**I/O ↔ 순수 경계**: 수량 계산은 `OrderSizer`(순수)로 분리한다. 현재는 `OrderExecutor` 안에
한 줄로 묻혀 있어 테스트가 주문 모킹 없이는 불가능하다.

## 6. 데이터 모델

### `OrderSizer.Sizing`

| 필드 | 타입 | 의미 |
|---|---|---|
| `qty` | BigDecimal | 주문 수량(정수, 내림). 0 이면 주문 금지 |
| `note` | String | 결정근거 1줄(주문로그·알림용). 예: `환율 1340.78 · 예산 600,000원=$447.50 · 예수금 $1,500.00 · 상한=예산 · $456.35 x 0주` |

환율/예수금이 없으면 `qty = 0` 과 사유가 담긴 `note` 를 낸다.

### 스키마 변경

**없음.** `note` 는 기존 주문로그 `message` 에 합쳐 넣는다(`compose(rationale, message)`).

## 7. 함수 명세

| 함수 | 책임(1줄) | 시그니처 | 입력 | 출력 | 에러/실패 | 복잡? |
|------|-----------|----------|------|------|-----------|-------|
| `OrderSizer.buyQuantity` | 시장별 주문 수량과 근거를 낸다 | `static Sizing buyQuantity(String market, BigDecimal budgetKrw, BigDecimal price, BigDecimal fxUsdKrw, BigDecimal cashUsd)` | §6 | `Sizing` | null/0 입력 → qty 0 + 사유 | **복잡** → `fn-buyQuantity.md` |
| `FxRateCache.usdKrw` | USD/KRW 환율(캐시) | `BigDecimal usdKrw()` | — | 환율 또는 null | 조회 실패 → null(실패 TTL 캐시) | **복잡** → `fn-usdKrw.md` |
| `OrderExecutor.buy` | 수량 계산 위임 + 실패 시 주문 포기 | 기존 시그니처 유지 | | boolean | 수량 0 → false | 단순(위임) |
| `OrderExecutor.usdCash` | USD 예수금 조회(예외→null) | `private BigDecimal usdCash()` | — | 금액 또는 null | 예외 삼키고 null | 단순 |

## 8. 흐름 / 알고리즘

```
buyQuantity(market, budgetKrw, price, fx, cashUsd):
  if (price == null || price <= 0)            → (0, "현재가 없음")
  if (budgetKrw == null || budgetKrw <= 0)    → (0, "예산 없음")

  if (!US):
      qty = floor(budgetKrw / price)
      return (qty, "원화 예산 ÷ 주가")

  if (fx == null || fx <= 0)                  → (0, "환율 조회 실패 — US 매수 보류")
  if (cashUsd == null || cashUsd < 0)         → (0, "USD 예수금 조회 실패 — US 매수 보류")

  budgetUsd = budgetKrw / fx                  (소수 2자리, 내림)
  spendUsd  = min(budgetUsd, cashUsd)
  qty       = floor(spendUsd / price)
  return (qty, 근거문)
```

`OrderExecutor.buy` 변경점:

```java
BigDecimal fx     = "US".equals(market) ? fxRates.usdKrw() : null;
BigDecimal cash   = "US".equals(market) ? usdCash()        : null;
OrderSizer.Sizing s = OrderSizer.buyQuantity(market, budget, currentPrice, fx, cash);
if (s.qty().signum() <= 0) {
    saveLogSafely(symbol, "BUY", "BUY_SIGNAL", dryRun, null, currentPrice, null, false, s.note(), ...);
    return false;
}
BigDecimal qty = s.qty();
```

기존 `수량 0(예산 부족)` 분기를 이 분기가 대체한다(사유가 더 구체적이다).

## 9. 엣지케이스 & 에러 처리

| 상황 | 처리 |
|---|---|
| 환율 null | US 매수 포기, 사유 기록(AC2). KR 은 영향 없음 |
| USD 예수금 null(429·인증 실패) | US 매수 포기(AC3) |
| USD 예수금 0 | qty 0 → 주문 안 함(AC5) |
| 예수금 < 환산예산 | 예수금이 상한(AC4) |
| 주가가 예산보다 큼(예: $1,040 MU vs $447) | qty 0 → 주문 안 함. **소수점 주문 미지원** |
| 환율이 비정상(예: 10) | 상한이 예수금이므로 과대주문은 막힌다. 다만 §13-1 에 기록 |
| 드라이런 | 수량 계산은 동일하게 수행한다(드라이런이 실거래와 다른 수량을 쓰면 검증 의미가 없다) |
| KR 종목 | 기존 식 그대로(AC6) |

## 10. 테스트 계획

### `OrderSizerTest` (순수)

| 케이스 | 매핑 |
|---|---|
| KR: 600,000 ÷ 18,940 = 31주 (실제 포지션 재현) | AC6 |
| US: 600,000원 ÷ 1340.78 = $447.50, ÷ $456.35 → 0주 | AC1 |
| US: 예산 $447.50, 주가 $41.29(SMCI) → 10주 | AC1 |
| US: 예수금 $100 < 예산 $447.50 → 예수금 상한 → 2주 | AC4 |
| US: 환율 null → 0주 + "환율" 사유 | AC2 |
| US: 예수금 null → 0주 + "예수금" 사유 | AC3 |
| 주가 null/0 → 0주 | §9 |
| 예산 null/0 → 0주 | §9 |
| 예수금 0 → 0주 | AC5 |
| 근거문에 환율·환산액·예수금이 들어간다 | AC8 |

### `FxRateCacheTest`

| 케이스 | 매핑 |
|---|---|
| 2회 호출 시 외부 조회 1회 | AC7 |
| 조회 실패 → null, 짧은 TTL 후 재시도 | AC2 |

### `OrderExecutorTest` 추가

| 케이스 | 매핑 |
|---|---|
| US 매수에서 환율 null 이면 `placeOrder` 가 호출되지 않는다 | AC2 |
| US 매수에서 예수금 상한이 수량에 반영된다 | AC4 |
| KR 매수 수량 회귀 | AC6 |

## 11. 배포 / 롤백

- **#885 보다 먼저 또는 함께** 배포한다(§1). 단독으로 #885 만 배포하면 US 매수가 열리면서
  환율 미반영 주문이 나갈 수 있다.
- 배포 후에도 `screener-markets=KR` 이므로 US 후보가 없어 즉시 주문은 나가지 않는다 —
  이중 안전장치.
- 롤백은 git revert.

## 12. 한계 / 미결 (원칙 §13)

1. **환율 소스가 야후 단일 경로다.** 야후가 틀린 값을 주면 과소/과대 주문이 가능하다.
   USD 예수금 상한이 과대 주문은 막지만 과소 주문(기회손실)은 막지 못한다.
   Toss 가 환율 엔드포인트를 제공하면 그쪽으로 바꾸는 것이 맞다.
2. **소수점 주문 미지원 — 슬롯예산($447)보다 비싼 종목은 1주도 못 산다.**
   2026-10-09 미장 스크리너 통과 17종목 중:
   - **매수 가능(≤$447, 10종목)**: INTC $106 · SMCI $41 · STM $52 · MRVL $272 ·
     NVDA $232 · GOOG $349 · PANW $404 · GOOGL $352 · CCL $26 · LH $317
   - **1주도 불가(>$447, 7종목)**: TSM $456(아깝게 초과) · AMAT $508 · MSFT $527 ·
     AMD $616 · META $717 · MU $1,040 · LLY $1,166

   과반은 살 수 있으므로 "크게 제한"은 아니다. 다만 TSM 처럼 근소하게 넘는 종목과
   대형 기술주 상위가 구조적으로 빠지므로, 슬롯예산 상향 또는 소수점 주문 지원을
   별도 과제로 둔다. 또한 1주 단위라 **예산 소진율이 낮다**(예: LH $317 → 1주 = 예산의
   71%, 나머지 29% 유휴).
3. **통합증거금 동작을 코드가 모른다.** 최솟값 전략으로 양쪽 체계에서 안전하게 만들었을
   뿐, 실제로 KRW 로 US 주식을 살 수 있는 계좌인지는 확인하지 않았다.
   실주문 1건으로 확인해야 한다.
