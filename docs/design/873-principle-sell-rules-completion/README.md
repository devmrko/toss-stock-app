# 설계서: 투자원칙 §4 미구현 조항 2개 완성 (#873)

> **상태**: Approved · **작성**: [AI] Architect · 2026-10-08
> **추적성** — Redmine: #873 · 근거: `docs/reference/investment-principles.md` §4 ·
>   구현: `TrailingStopCalculator`, `IndexLagChecker`(신규), `IndexLagAlertService`(신규),
>   `AutoTradeProperties`, `application.yml`, `AutoTradeScheduler` ·
>   테스트: `IndexLagCheckerTest`(신규), `TrailingStopCalculatorTest`, `AutoTradeSchedulerTest`

## 1. 목적 (Why)

#871 에서 원칙에 **없는** 매도(NEWS_FADED)를 제거하고 #872 로 논거 무효 매도를 넣었다.
남은 것은 **원칙에 있는데 구현되지 않은** 조항 2개다.

§4 '규칙 요약' 원문 대조:

| 조항 | 원문 표현 | 구현 |
|------|-----------|------|
| 손절 | **필수 하드룰** — 진입가 −10% 에서 자동 손절 | `HARD_STOP` **있음** |
| 지수 하락기 보정 | 지수 대비 −10% 언더퍼폼 시 교체 **고려** | **없음** |
| 추적 손절 | 최근 고점 −10%. 대박 구간(+300% 등)은 −15~20% 로 완화 **가능** | `TRAIL_STOP` **있음**, 완화 조항 **없음** |

## 2. 조치 1 — 지수 열위는 **알림**으로 구현한다 (자동매도 아님)

### 2.1 왜 알림인가

원칙이 손절은 "**필수 하드룰**"로, 이 조항은 "교체 **고려**"로 쓰고 있다.
**문서 스스로 강제성을 구분**하므로 자동 하드 매도로 만들면 **과잉 구현**이다.

게다가 이 프로젝트의 지금까지 실현손실은 **과도한 매도·왕복**에서 나왔다
(엠플러스 45회 왕복, 수수료 61,323원 / 2026-10-08 청산 5건 중 4건이 만료 매도).
매도 트리거를 늘리는 방향은 신중해야 한다.

→ 조건 충족 시 Discord 알림만 보내고 **교체 판단은 사람이 한다**.

> 자동매도로 올리고 싶다면 `IndexLagAlertService` 가 알림 대신
> `orderExecutor.sell(..., ExitReason.INDEX_LAG, ...)` 를 호출하면 된다.
> 지금 `ExitReason` 에 `INDEX_LAG` 를 **추가하지 않는다** — 쓰지 않는 매도 사유를
> enum 에 두면 "구현된 매도 규칙"으로 오독된다.

### 2.2 판정 조건 (둘 다 충족)

| 조건 | 식 | 근거 |
|------|-----|------|
| **지수 하락기** | `indexReturnPct < 0` | 원칙은 "지수 **하락기**에는 …"으로 조건부다 |
| **언더퍼폼** | `stockReturnPct − indexReturnPct <= −10` | 원칙 "지수 대비 −10% 언더퍼폼" |

- 기간: 기존 `relative-strength-window-days`(운영값 20일) 재사용 — 매수 게이트의
  상대강세 판정과 **같은 창**을 써야 판단이 일관된다.
- 지수: KR `069500`(KODEX200), US `SPY`. US 는 `ValuationClient.getIndexReturnPct`
  (야후 경로) — Toss 캔들 API 가 US ETF 를 지원하지 않는다(#828 QA 실측: SPY/QQQ/VOO/
  IVV/DIA 전부 0건).
- 데이터 부족(둘 중 하나라도 null) → **알림 없음**(fail-silent). 알림은 근거가
  확실할 때만 보낸다.

### 2.3 중복 알림 방지

같은 종목에 **1일 1회**. 틱이 분 단위로 돌기 때문에 방지 장치가 없으면 장중 390회
알림이 간다. 메모리 맵(`symbol → LocalDate`)으로 충분하다 — 재기동 시 초기화되지만
그 비용은 "재기동 당일 알림 1회 추가"뿐이고, 영속화할 가치가 없다.

### 2.4 알림 위치

보유 포지션 점검(`processHolding`) 안에서 한다 — 이미 포지션·현재가·시장 개장 여부를
들고 있고, 보유 중인 종목만 대상이기 때문이다. 별도 스케줄러를 두면 같은 조회를
두 번 한다.

매도 판정(`exit != NONE`)이 났으면 알림을 **보내지 않는다** — 어차피 파는 포지션에
"교체 고려" 알림은 잡음이다.

## 3. 조치 2 — 추적손절 멀티배거 완화

원칙: 최고가 대비 −10%, 단 **"대박 구간(+300% 등)은 −15~20% 로 완화 가능"**.
멀티배거를 10% 흔들림에 털리지 않게 하는 조항이다.

| 항목 | 값 | 근거 |
|------|-----|------|
| 임계 | 피크가 진입가 대비 **+300%** 이상 | 원칙 문서의 예시 수치 그대로 |
| 완화 후 추적폭 | **15%** | 원칙이 준 15~20% 중 **보수적인 쪽** |
| 측정 기준 | **피크**(현재가 아님) | 현재가로 재면 가격이 오갈 때 추적폭이 깜빡인다. 한 번 멀티배거 구간에 들어가면 완화를 유지한다 |

> **솔직한 한계**: 이 봇은 슬롯당 60만원으로 수 시간~수일 보유한다. +300% 포지션이
> 나올 가능성은 사실상 없고 **당장은 발화하지 않는 코드**다. 그래도 원칙에 있는
> 조항이고 비용이 작아 공백을 닫는다. 임계값은 설정으로 빼 두어 나중에 조정 가능하게 한다.

### 3.1 매도 판정과 로그의 손절선 일치

`TrailingStopCalculator.decide` 의 **시그니처는 바꾸지 않는다**(기존 테스트·호출부 보존).
대신 순수 함수 `effectiveTrailPct(...)` 를 추가하고 **호출부가 한 번 해소**해
`decide` 와 `trailFloor` 에 **같은 값**을 넘긴다.

```java
double trailPct = TrailingStopCalculator.effectiveTrailPct(peak, p.getEntryPrice(),
        props.trailStopPct(), props.multibaggerGainPct(), props.multibaggerTrailStopPct());
ExitReason exit = TrailingStopCalculator.decide(current, peak, p.getEntryPrice(),
        props.hardStopPct(), trailPct);
...
TrailingStopCalculator.trailFloor(peak, trailPct)   // 결정근거 로그도 같은 값
```

이렇게 하지 않으면 "15% 로 판정했는데 로그에는 10% 손절선"이 찍혀 사후 검증이 깨진다.

## 4. 함수 등재 (Function Registry)

| 함수 | 파일 | 책임 | I/O | 비고 |
|------|------|------|-----|------|
| `TrailingStopCalculator.effectiveTrailPct(BigDecimal peak, BigDecimal avgCost, double trailPct, double multibaggerGainPct, double multibaggerTrailPct)` | 기존 수정 | 멀티배거 구간이면 완화폭, 아니면 기본폭 | **순수** | |
| `IndexLagChecker.lagsIndex(Double stockReturnPct, Double indexReturnPct, double thresholdPct)` | `autotrade/IndexLagChecker.java` (신규) | §2.2 두 조건 판정 | **순수** | null 이면 false |
| `IndexLagAlertService.checkAndAlert(AutoTradePosition)` | `autotrade/IndexLagAlertService.java` (신규) | 수익률 조회 → 판정 → 1일 1회 알림 | I/O | |
| `IndexLagAlertService.shouldAlertToday(String symbol, LocalDate today)` | 동일 | 중복 방지 | 상태 변경 | 메모리 맵 |
| `AutoTradeScheduler.processHolding` | 기존 수정 | `effectiveTrailPct` 해소 + 알림 호출 | I/O | |
| `AutoTradeProperties` | 기존 수정 | 설정 3개 추가 | — | 위치 레코드 — 테스트 3곳 수정 |

`effectiveTrailPct`·`lagsIndex` 는 단순 술어라 별도 함수 설계서를 두지 않는다
(규칙이 §2.2·§3 표로 등재돼 있다).

### 4.1 설정 추가

```yaml
auto-trade:
  multibagger-gain-pct: ${AUTO_TRADE_MULTIBAGGER_GAIN_PCT:300.0}
  multibagger-trail-stop-pct: ${AUTO_TRADE_MULTIBAGGER_TRAIL_STOP_PCT:15.0}
  index-lag-alert-pct: ${AUTO_TRADE_INDEX_LAG_ALERT_PCT:10.0}
```

## 5. 인수조건 (Acceptance)

1. 지수 하락기 + 지수대비 −10%p 이하면 알림이 간다.
2. 지수가 **상승기**면 언더퍼폼이어도 알림이 가지 **않는다**(원칙은 "지수 하락기" 조건부).
3. 같은 종목에 하루 두 번 알림이 가지 않는다.
4. 지수 열위는 **매도를 유발하지 않는다**.
5. 피크가 진입가 대비 +300% 이상이면 추적손절폭이 **15%** 로 완화된다.
6. +300% 미만이면 기존 **10%** 그대로다.
7. 완화된 추적선이 **매도 판정과 결정근거 문자열에 동일하게** 반영된다.
8. 수익률 데이터가 없으면 알림을 보내지 않는다.

## 6. 테스트 계획

`IndexLagCheckerTest`:
- 지수 −5%, 종목 −16% (차이 −11%p) → **true**
- 지수 −5%, 종목 −10% (차이 −5%p) → false (언더퍼폼 부족)
- 지수 **+5%**, 종목 −6% (차이 −11%p) → **false** (인수조건 2 — 상승기)
- 지수 −5%, 종목 −15% (차이 정확히 −10%p) → true (경계 포함)
- 둘 중 하나 null → false (인수조건 8)

`TrailingStopCalculatorTest`:
- 피크/진입 = 4.0 (+300%) → `effectiveTrailPct` 가 완화폭 반환 (인수조건 5)
- 피크/진입 = 3.99 → 기본폭 (인수조건 6)
- 피크/진입 = 1.0 → 기본폭
- `avgCost` 0·null → 기본폭(방어)
- 완화폭이 적용된 `decide` 가 −12% 하락에서 `NONE`, −16% 에서 `TRAIL_STOP`

`AutoTradeSchedulerTest`:
- 지수 열위 조건이어도 `orderExecutor.sell` 미호출 (인수조건 4)
- 멀티배거 포지션에서 결정근거 문자열의 트레일 손절선이 15% 기준 (인수조건 7)

## 7. 리스크 / 되돌리기

| 리스크 | 완화 |
|--------|------|
| 장중 알림 폭주 | 종목당 1일 1회(§2.3). 보유 종목만 대상이라 최대 5건/일 |
| 멀티배거 완화가 손실을 키움 | 임계가 +300% 라 진입가 대비로는 여전히 큰 이익 구간이다(+300% → 피크 −15% 면 +240%). 하드손절(진입가 −10%)은 그대로 작동 |
| 설정 레코드 필드 추가로 테스트 3곳 깨짐 | 같은 커밋에서 함께 수정(함수 등재표에 명시) |
| 지수 열위 판정이 매수 게이트의 상대강세와 어긋남 | 같은 창(`relative-strength-window-days`)과 같은 지수를 쓴다 |

되돌리기: 알림 호출 1줄 제거, `effectiveTrailPct` 를 `props.trailStopPct()` 로 되돌림.

## 8. 범위 밖

- **분당 매매 vs 원칙 §0/§6 주 1회 주기 정합** — 별도 판단 필요. 분당 틱 자체는
  손절 감시에 필요하므로 단순히 주기를 늘리면 리스크 관리가 나빠진다. 줄여야 할 것은
  **매수 빈도**이고, #869·#871 로 이미 크게 줄었다(후보 0건, 뉴스 통과 2.5%).
  실제 회전율을 한 주 관측한 뒤 추가 조치가 필요한지 판단하는 것이 맞다.
- `ExitReason.INDEX_LAG` 추가 — 쓰지 않는 매도 사유를 enum 에 두지 않는다(§2.1).
