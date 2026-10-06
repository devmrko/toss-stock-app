# 레퍼런스: 레인지(박스권) 스윙매매 트랙 (#818)

> **문서 성격**: 구현된 공개 API·설정·DB 사양(Diátaxis Reference). 설계 의도/근거는
> `docs/design/818-range-trade-swing/README.md`(+ `fn-*.md`)를 본다.
> **구현 커밋 기준**: 2026-10-01 · **패키지**: `com.cloudhandson.tossstock.rangetrade`
> **관련**: #808 모멘텀 트랙(`autotrade` 패키지)과 **예산·슬롯·DB·스케줄러가 완전히 분리**되어 있다.

## 1. 공개 함수

### `RangeBoundChecker.evaluate(List<DailyOhlcv> window, RangeTradeProperties props) → Result`
- **순수 함수**(static). 입력 리스트를 변형하지 않는다(내부에서 복사·정렬).
- `Result(boolean isRangeBound, BigDecimal low, BigDecimal high)` — `isRangeBound=false`면 `low`/`high`는 `null`.
- 판정: ① 유효 일봉 `windowDays+1`개 이상 → ② `low`=최저 저가, `high`=최고 고가 →
  ③ `widthPct = (high-low)/((high+low)/2)*100` 이 `[minWidthPct, maxWidthPct]`(경계 포함) →
  ④ 전반부/후반부 종가 평균차(중간값 대비 %)가 `maxTrendDriftPct` 이하(경계 포함).
- 홀수 윈도우는 중간 1개를 **전반부(과거쪽)** 에 포함. 고가·저가·종가·거래일 중 하나라도 `null`인
  일봉은 제외하며, 그 결과 개수가 부족해지면 `isRangeBound=false`(fail-closed).

### `RangeTradeSignal.decide(BigDecimal current, BigDecimal rangeLowAtEntry, BigDecimal rangeHighAtEntry, boolean holding, RangeTradeProperties props) → Signal`
- **순수 함수**(static). `Signal ∈ {BUY, PROFIT_TAKE, RANGE_BREAKDOWN, NONE}`.
- `holding=false`: `current <= entryCeiling` → `BUY`, 아니면 `NONE`.
- `holding=true`: `current >= profitFloor` → `PROFIT_TAKE`, 아니면 `current <= breakdownFloor` →
  `RANGE_BREAKDOWN`, 아니면 `NONE`. **경계값은 모두 포함**.
- `IllegalArgumentException`: `current`가 `null`/0 이하, 밴드가 `null`, `rangeLow >= rangeHigh`.
- 임계값 조회용 보조 함수(모두 static, 순수):
  - `entryCeiling(rangeLow, props)` = `rangeLow × (1 + entryZonePct/100)`
  - `profitFloor(rangeHigh, props)` = `rangeHigh × (1 - exitZonePct/100)`
  - `breakdownFloor(rangeLow, props)` = `rangeLow × (1 - breakdownPct/100)`
- 기본 파라미터(10/10/5)에서 `breakdownFloor < entryCeiling < profitFloor` 가 성립 — 진입구간
  어디에서 사도 즉시 손절/익절이 터지지 않는다.

### `BadNewsGate.hasStrongBadNews(String symbol) → boolean`
- 활성 뉴스(`StockNewsMapper.active(symbol, 20)`)에서 해당 종목 레벨이 **S1/S2(악재)** 면 `true`.
- `true`면 신규 매수 차단, 보유 중이면 가격과 무관하게 즉시 `BAD_NEWS` 매도.
- **조회 실패 시 `false`(fail-open — 차단하지 않음)**: API 장애로 영원히 매수 못 하거나, 반대로
  장애를 악재로 오인해 보유분을 전량 매도하는 쪽이 더 위험하다는 판단(설계서 §9).

### `EarningsCalendarGate.hasUpcomingEarnings(String symbol, int holdingHorizonDays) → boolean`
- 데이터 소스: 야후 `quoteSummary?modules=calendarEvents` → `calendarEvents.earnings.earningsDate[].fmt`.
  국내 코드는 `universe.market`으로 서픽스 결정(KOSDAQ→`.KQ`, 그 외→`.KS`).
- `today ~ today+holdingHorizonDays` 안에 실적발표일이 있으면 `true`(후보 제외).
- **데이터 없음/조회 실패 시 `false`(fail-open)**. 추정치(`isEarningsDateEstimate=true`)도 차단 대상.
- 네이버 모바일 API에는 쓸 수 있는 실적발표 예정일 필드가 **없음**(실측 근거는 설계서 §12).

### `RangeOrderExecutor`
- `buy(String symbol, BigDecimal budget, BigDecimal currentPrice, BigDecimal rangeLow, BigDecimal rangeHigh, String rationale) → boolean`
  — v1은 KR만이라 market 파라미터가 없다(`market='KR'` 고정, 통화 KRW).
  `budget > perSymbolBudget`이면 주문 자체를 만들지 않고 `false`. 수량은 `budget/price` 내림(정수 주),
  0주면 `false`. 진입 시점 밴드(`rangeLow`/`rangeHigh`)를 포지션에 **고정 저장**(이후 갱신 금지).
- `sell(RangeTradePosition position, RangeExitReason reason, BigDecimal currentPrice, String rationale) → boolean`
  — 드라이런 여부는 **그 포지션의 진입 시점 플래그**를 따른다(가상 진입이 실매도로 바뀌지 않게).
- **이중 드라이런 안전장치**: `range-trade.dry-run`(설정)과 `range_trade_state.dry_run`(DB) 중
  하나라도 true면 드라이런. 상태 행이 없으면(조회 결과 `null`) 드라이런.
- **감사로그와 포지션 기록 분리**: `range_trade_order_log` INSERT가 실패해도 예외를 삼키고
  ERROR 로그만 남긴 뒤 포지션 기록/청산 처리를 계속한다(#808 2026-10-01 중복매수 사고 교훈).
- 주문 실패 시 **재시도하지 않는다**(#808과 동일 원칙).
- `rationale`(#832): 호출부가 조립한 결정근거 스냅샷을 `message` 앞부분에 남긴다
  (`<rationale> | <주문결과>`, 500자 절삭). `null`/공백이면 기존 문구만. 예산 상한 초과·수량 0
  경로는 기존 문구 유지. 상세: `docs/reference/decision-rationale.md`.

### `RangeTradeScheduler.tick()`
- `@Scheduled(cron = "${range-trade.cron:0 0 16 * * MON-FRI}", zone = "Asia/Seoul")` — 1일 1회 장마감 후.
- 순서: ① 보유 포지션 점검(종목별 try/catch — 한 종목 예외가 전체 틱을 죽이지 않음) →
  ② 서킷브레이커 트립 시 신규매수 중단 → ③ 빈 슬롯 있으면 유니버스 스캔.
- 스캔 2단계: `DailyOhlcvMapper.rangeStatsBatch`(쿼리 1번, 전 KR 종목 윈도우 통계)로 1차 스크리닝 →
  살아남은 종목만 실제 일봉을 청크(≤900종목/쿼리)로 받아 `LiquidityChecker`+`RangeBoundChecker`로 최종 판정.
- 현재가 소스: **보유 포지션은 `PriceCache`(실시간), 신규 후보는 윈도우의 최신 종가**(설계서 §12 참고).
- 로깅: 스캔 단계 대량 스킵 = DEBUG, 최종 후보가 악재/실적 게이트로 막힌 건 = INFO,
  보유 판정 결과 = INFO, 주문 시도 = `range_trade_order_log`(결정근거 포함 — #832).

### `DailyOhlcvMapper.rangeStatsBatch(LocalDate fromDate, int windowBars) → List<DailyRangeStats>`
- KR 유니버스(`universe` 조인) 전 종목에 대해 종목별 최근 `windowBars` 거래일 통계를 한 번에 반환:
  `barCount`, `maxHigh`, `minLow`, `lastClose`, `firstHalfAvgClose`, `secondHalfAvgClose`.
- 전반부(과거쪽) = `rn > FLOOR(windowBars/2)` — 홀수면 중간 1개가 전반부(Java 쪽 규칙과 동일).
- `barCount < windowBars` 종목은 반환하지 않는다. 고가/저가/종가가 `null`인 행은 집계에서 제외.

### `UniverseMapper.findMarketBySymbol(String symbol) → String`
- `universe.market`(KOSPI/KOSDAQ/ETF). 없으면 `null`. 야후 티커 서픽스 결정용.

## 2. 설정 (`application.yml` → `range-trade:`)

| 키 | 기본값 | 의미 |
|---|---|---|
| `dry-run` | `true` | 코드측 드라이런(DB 플래그와 OR) |
| `total-budget` | 1,000,000 | 트랙 전체 예산(모멘텀과 분리) |
| `max-symbols` | 2 | 동시 보유 슬롯 |
| `per-symbol-budget` | 500,000 | 종목당 예산 상한(초과 시 주문 차단) |
| `window-days` | 60 | 밴드 산출 윈도우(거래일) — 필요 일봉 = 61개 |
| `min-width-pct` | 26.0 | 최소 밴드폭(중간값 기준 %) — Ellman ROO 역산 |
| `max-width-pct` | 50.0 | 최대 밴드폭 |
| `max-trend-drift-pct` | 15.0 | 전반부↔후반부 종가 평균차 허용치 |
| `entry-zone-pct` | 10.0 | 하단에서 몇 % 위까지 매수 허용 |
| `exit-zone-pct` | 10.0 | 상단에서 몇 % 아래부터 익절 |
| `breakdown-pct` | 5.0 | 진입 시점 하단에서 몇 % 아래면 손절 |
| `min-avg-trading-value` | 300,000,000 | 유동성 게이트(평균 거래대금, KRW) |
| `holding-horizon-days` | 30 | 실적발표 제외 게이트의 예상 보유기간 |
| `webhook-url` | `${DISCORD_WEBHOOK_URL:}` | 비어 있으면 알림 비활성 |
| `cron` | `0 0 16 * * MON-FRI` | 장마감 후 1일 1회 |

모든 키는 `RANGE_TRADE_*` 환경변수로 덮어쓸 수 있다(값은 `.env`, 하드코딩 금지).

## 3. DB (`src/main/resources/db/range_trade.sql`, 멱등 DDL)

- `range_trade_position` — `status`(HOLDING/EXITED), `entry_*`, **`range_low_at_entry`/`range_high_at_entry`
  (진입 시점 고정값, 트레일링 금지)**, `budget_allocated`, `exit_*`, `dry_run`.
- `range_trade_state` — 싱글턴(id=1): `total_budget`, `max_symbols`, `per_symbol_budget`, `dry_run`(기본 1),
  `circuit_breaker_tripped`(자동 트립 로직은 v1 미구현 — 수동 플래그).
- `range_trade_order_log` — 감사로그. `toss_order_id VARCHAR2(200)`(실제 Toss orderId 86자 사례 반영),
  `message VARCHAR2(500)`(초과 시 코드에서 잘라 넣음).
- `range_trade_candidate` 테이블은 **없다**(매일 유니버스 전체를 스캔하는 방식).
- 기동 시 `SchemaInitializer`가 `db/range_trade.sql`을 멱등 실행.

## 4. 테스트

| 테스트 | 건수 | 커버 |
|---|---|---|
| `RangeBoundCheckerTest` | 12 | 왕복 패턴 통과/밴드값, 추세 탈락, 폭 상·하한, 데이터 부족, 임계값 경계, 홀수 윈도우, 순수성 |
| `RangeTradeSignalTest` | 10 | BUY/PROFIT_TAKE/RANGE_BREAKDOWN/NONE, 경계 포함, 입력 검증 예외, 진입 직후 즉시청산 불가, 최악 왕복 수익성 |
| `RangeTradeSchedulerTest` | 10 | 상태행 없음→무동작, 한 종목 예외가 다른 종목/스캔을 막지 않음, 악재 즉시매도, 하단이탈 손절, 서킷브레이커 트립 시 매도만, 빈 슬롯 없음→스캔 생략, 신규 평가손익 트립/정상, 매도·매수에 전달되는 결정근거 문자열(#832) |
| `RangeOrderExecutorTest` | 15 | 설정/DB 이중 드라이런, 상태행 없음→드라이런, 예산 상한·0주 차단, 감사로그 실패와 포지션/청산 기록 분리, 주문 실패 시 포지션 미기록·재시도 없음, 매도의 포지션 드라이런 우선, exit_reason 기록, message의 결정근거 조합·500자 절삭(#832) |
