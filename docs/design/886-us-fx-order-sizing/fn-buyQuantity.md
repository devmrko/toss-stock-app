# 함수 설계서: `OrderSizer.buyQuantity` (#886)

> **부모 설계서**: ./README.md · **상태**: Draft
> **작성**: [AI] Architect · **구현**: `autotrade/OrderSizer.java:buyQuantity`
> **테스트**: `autotrade/OrderSizerTest.java`

## 1. 시그니처

```java
public static Sizing buyQuantity(String market, BigDecimal budgetKrw, BigDecimal price,
                                  BigDecimal fxUsdKrw, BigDecimal cashUsd)

public record Sizing(BigDecimal qty, String note) {}
```

## 2. 책임 (단일 책임, 1줄)

주문 수량과 그 결정근거를 낸다 — **리스크 경로의 순수 함수**이고 I/O 를 하지 않는다.

## 3. 입력

| 파라미터 | 타입 | 제약/검증 | 설명 |
|----------|------|-----------|------|
| `market` | String | `US` 만 특별 취급, 그 외는 원화 경로 | 종목 시장 |
| `budgetKrw` | BigDecimal | null·0 이하 → qty 0 | 슬롯예산(원) |
| `price` | BigDecimal | null·0 이하 → qty 0 | 현재가. **US 는 달러** |
| `fxUsdKrw` | BigDecimal | US 에서 null·0 이하 → qty 0 | USD/KRW. KR 에서는 무시 |
| `cashUsd` | BigDecimal | US 에서 null·음수 → qty 0 | USD 예수금. KR 에서는 무시 |

## 4. 출력

- **반환**: `Sizing(qty, note)`. `qty` 는 **정수(내림)**, 0 이면 호출부가 주문하지 않는다.
  `note` 는 주문로그·알림에 그대로 들어가는 1줄 근거다.
- **부수효과**: 없음 — **순수 함수**. 로깅도 하지 않는다.

## 5. 동작 / 알고리즘

```
1) price  가 null 또는 <= 0  → (0, "현재가 없음 — 수량 계산 불가")
2) budget 가 null 또는 <= 0  → (0, "슬롯예산 없음")

3) KR 경로(market != "US"):
     qty = floor(budgetKrw / price)
     note = "예산 {budget}원 ÷ {price}원 = {qty}주"
     return

4) US 경로:
   4-1) fx       null·<=0 → (0, "환율 조회 실패 — US 매수 보류(#886)")
   4-2) cashUsd  null·<0  → (0, "USD 예수금 조회 실패 — US 매수 보류(#886)")
   4-3) budgetUsd = budgetKrw / fx         (스케일 2, 내림)
   4-4) spendUsd  = min(budgetUsd, cashUsd)
   4-5) qty = floor(spendUsd / price)
   4-6) note = "환율 {fx} · 예산 {budget}원=${budgetUsd} · 예수금 ${cashUsd}
                · 상한={예산|예수금} · ${price} x {qty}주"
```

### 왜 최솟값인가

원화 환산액만 보고 주문하면 계좌에 달러가 없을 때 주문 거부나 의도치 않은 환전이 날 수
있고, 예수금만 보면 "슬롯당 60만원" 리스크 규율이 깨진다. **두 제약을 동시에 만족**하는
유일한 값이 최솟값이다. 상한이 무엇이었는지를 `note` 에 남겨 사후에 구분할 수 있게 한다.

### 왜 fail-closed 인가

환율이나 예수금을 모르는 상태에서 주문하면 **수량이 400배로 틀릴 수 있다** — 이 함수가
고치려는 바로 그 결함이다(실측: 600,000 ÷ $456.35 = 1,315주 ≈ 8억원). 반면 매수를
보류하면 비용은 기회비용뿐이다. 비대칭이 명확하므로 보류한다.

## 6. 에러 & 실패 모드

| 조건 | 처리 | 반환 |
|------|------|------|
| `price` null/≤0 | 수량 0 | `(0, "현재가 없음…")` |
| `budgetKrw` null/≤0 | 수량 0 | `(0, "슬롯예산 없음")` |
| US & `fx` null/≤0 | 수량 0 | `(0, "환율 조회 실패…")` |
| US & `cashUsd` null/<0 | 수량 0 | `(0, "USD 예수금 조회 실패…")` |
| 계산 결과 0주 | 수량 0 | `(0, note)` — 사유에 환율·예산·주가가 남는다 |

**예외를 던지지 않는다.** 호출부(`OrderExecutor.buy`)가 주문로그를 남기고 `false` 를
반환하는 단일 경로를 타게 해서, 실패가 조용히 사라지지 않도록 한다.

## 7. 엣지케이스

- **주가 > 환산예산** (TSM $456 vs $447.50) → 0주. 1주도 못 산다(소수점 주문 미지원).
  부모 설계서 §12-2 에 영향 범위를 적어 뒀다.
- **예수금 0** → 0주.
- **예수금 < 주가** (예: $100 예수금, $317 LH) → 0주.
- **환율이 비정상적으로 작음**(예: 10) → `budgetUsd` 가 60,000$ 로 커지지만 `cashUsd`
  상한이 걸려 과대주문은 나가지 않는다. 다만 예수금 전액을 쓰게 되므로 §12-1 에 기록.
- **KR 인데 `fx`/`cashUsd` 가 들어옴** → 무시한다(KR 경로는 두 값을 읽지 않는다).
- **반올림** — 전부 `RoundingMode.DOWN`. 예산을 넘기지 않는 방향이다.
- **드라이런** — 이 함수는 드라이런을 모른다. 호출부가 같은 수량으로 드라이런을 기록한다
  (드라이런이 실거래와 다른 수량을 쓰면 검증 의미가 없다).

## 8. 복잡도 / 성능

- O(1). `BigDecimal` 나눗셈 2회.
- 호출 빈도: 매수 시도 1건당 1회(틱당 최대 열린 슬롯 수). I/O 없음.
