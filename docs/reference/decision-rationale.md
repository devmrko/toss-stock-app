# 레퍼런스: 매수/매도 결정근거 스냅샷 로깅 (#832)

> **문서 성격**: 구현된 공개 API·메시지 포맷 사양(Diátaxis Reference). 설계 의도/근거는
> `docs/design/832-decision-rationale-logging/README.md`.
> **구현 기준**: 2026-10-06 · **패키지**: `com.cloudhandson.tossstock.autotrade`(#808),
> `...rangetrade`(#818)

주문 감사로그의 `message` 컬럼에 "그 시점에 무엇을 보고 사고/팔았는지"를 남긴다. 신규 컬럼·신규
외부 API 호출은 없고, 판정에 이미 쓴 값만 다시 포맷한다.

## 1. message 포맷

`<rationale> | <outcome>` — `outcome`은 기존 문구(`실주문 체결` / `드라이런 — 실주문 안 함` /
`주문 실패: <원인>`)를 그대로 쓴다. 최종 문자열은 `VARCHAR2(500)` 방어로 500자에서 잘린다.

| 경로 | message |
|---|---|
| 매수 성공(#808) | `PER11.6/PBR0.47(저평가) 펀더3/5(실적X재무O배당O유동O강도X) 인기:가격+2.34% 뉴스:S5 "안랩, 보안주 급등" \| 실주문 체결` |
| 매도(#808) | `트레일스탑(피크235000→현재210500,-10.43%) 진입200000 수익+5.25% 스탑211500(하드180000/트레일211500) \| 실주문 체결` |
| 매수 성공(#818) | `밴드100000~130000 진입상한110000 매수가100000(밴드내0%) \| 드라이런 — 실주문 안 함` |
| 매도(#818) | `익절(익절선117000) 현재120000 진입105000 수익+14.29% 밴드100000~130000 익절선117000/손절선95000 \| 실주문 체결` |
| 주문 실패 | `<rationale> \| 주문 실패: <원인>` — "왜 팔려고/사려고 했는지"는 남긴다 |
| 예산 상한 초과 / 수량 0 | **기존 문구 그대로**(`예산 상한 초과`, `수량 0(예산 부족)`) — 주문 자체가 안 만들어진 경로 |

숫자 표기 규칙(4개 포매터 공통): 소수 2자리 반올림 후 불필요한 0 제거(`11.60`→`11.6`),
변동률은 부호 포함(`+5.25%`, `-10.43%`), `null`·결측·0으로 나누기는 모두 `N/A`.

## 2. 공개 함수 (전부 static 순수 함수 — 부수효과 없음, 예외 안 던짐)

### `BuyRationale.describe(Valuation v, boolean cheap, boolean rerateCatalyst, String catalystHeadline, FundamentalScore score, boolean volumeSpike, double volumeRatio, boolean priceMove, double priceMovePct, String triggerNewsTitle) → String`
- 4개 절을 공백으로 이어 붙인다: `PER…/PBR…(통과경로)`, `펀더n/5(실적O재무O배당O유동O강도O)`,
  `인기:…`, `뉴스:…`.
- 통과경로: `(저평가)` / `(재평가촉매)` / `(저평가,재평가촉매)`, 둘 다 아니면 `(통과경로N/A)`.
  `catalystHeadline`이 있으면 `(재평가촉매:"제목")` — 단 `AutoTradeScheduler`는 "뉴스:" 절과
  중복되므로 이 인자를 비워서 호출한다.
- 인기: `거래량x5.24배`(volumeSpike), `가격+2.34%`(priceMove), 둘 다면 쉼표로 연결, 없으면 `N/A`.
  **true 로 켜진 트리거의 수치만** 적는다(데이터 부족 시 수치는 0이고 애초에 false).
- 뉴스/촉매 문자열은 연속 공백·개행을 공백 1칸으로 평탄화하고 60자에서 `…`로 자른다.

### `SellRationale.describe(ExitReason reason, BigDecimal entryPrice, BigDecimal peakPrice, BigDecimal currentPrice, BigDecimal hardFloor, BigDecimal trailFloor) → String`
- 형태: `<사유>(피크P→현재C,피크대비%) 진입E 수익진입대비% 스탑<바인딩>(하드H/트레일T)`.
- 사유 라벨: `하드스탑`/`트레일스탑`/`뉴스소멸`/`서킷브레이커`/`수동매도`/`사유없음`(`reason=null`→`매도`).
- 바인딩 스탑 = `max(hardFloor, trailFloor)`(한쪽이 null이면 다른 쪽, 둘 다 null이면 `스탑N/A`).

### `RangeBuyRationale.describe(BigDecimal rangeLow, BigDecimal rangeHigh, BigDecimal entryCeiling, BigDecimal currentPrice) → String`
- 형태: `밴드L~H 진입상한C 매수가P(밴드내x%)`. 밴드 내 위치 = `(P-L)/(H-L)×100`(폭 0이면 `N/A`).

### `RangeSellRationale.describe(RangeExitReason reason, BigDecimal entryPrice, BigDecimal rangeLow, BigDecimal rangeHigh, BigDecimal profitFloor, BigDecimal breakdownFloor, BigDecimal currentPrice) → String`
- 형태: `<사유>(발동임계선) 현재C 진입E 수익% 밴드L~H 익절선PF/손절선BF`.
- 사유 라벨: `익절`/`밴드이탈손절`/`악재매도`/`수동매도`(`null`→`매도`). 괄호 안은 발동한 쪽의
  임계선(`익절선…`/`손절선…`)이고, 가격과 무관한 악재·수동 매도는 `가격무관`.

### 판정값 추출 접근자(기존 판정 로직 불변, 로깅용 수치 노출)
- `PopularityChecker.volumeRatio(List<DailyOhlcv> rows, int window) → double` — 평균 대비 거래량
  배수. 데이터 부족/평균 0 → `0`. `isVolumeSpike`와 동일 공식(일치성 테스트로 고정).
- `PopularityChecker.priceMovePct(List<DailyOhlcv> rows) → double` — 당일 종가의 전일 대비 %.
  데이터 부족 → `0`. `isPriceMoveSignificant`와 동일 공식.
- `TrailingStopCalculator.hardFloor(BigDecimal avgCost, double hardStopPct) → BigDecimal`,
  `TrailingStopCalculator.trailFloor(BigDecimal peak, double trailPct) → BigDecimal` —
  `decide`가 내부에서 쓰는 바로 그 값(식을 추출해 공개).

## 3. 주문 실행기 시그니처 변경

| 전 | 후 |
|---|---|
| `OrderExecutor.buy(String symbol, String market, BigDecimal budget, BigDecimal currentPrice)` | `… , String rationale)` |
| `OrderExecutor.sell(AutoTradePosition p, ExitReason reason, BigDecimal currentPrice)` | `… , String rationale)` |
| `RangeOrderExecutor.buy(String symbol, BigDecimal budget, BigDecimal currentPrice, BigDecimal rangeLow, BigDecimal rangeHigh)` | `… , String rationale)` |
| `RangeOrderExecutor.sell(RangeTradePosition p, RangeExitReason reason, BigDecimal currentPrice)` | `… , String rationale)` |

`rationale`이 `null`/공백이면 기존 message 만 남는다(동작 호환). 주문 실행·드라이런 분기·포지션
기록 로직은 바뀌지 않았다.

## 4. 호출부

- `AutoTradeScheduler#scanCandidates` → `buyRationale(...)`(private): 게이트 통과에 쓴
  `valuation`/`cheap`/`rerateCatalyst`/`score`/`recent`를 그대로 재사용 + `topActiveNewsLabel(symbol)`
  이 `StockNewsMapper.active(symbol, 5)`(로컬 DB 1회)로 최고 레벨 활성 뉴스를 `S5 "제목"`(제목 45자
  상한)으로 만든다. **이 조회가 실패하면 예외를 삼키고 `null`** → `뉴스:N/A`(로깅 때문에 매수가
  막히지 않게).
- `AutoTradeScheduler#processHolding` → `SellRationale.describe(exit, entryPrice, peak, current,
  hardFloor(entryPrice, hardStopPct), trailFloor(peak, trailStopPct))`.
- `RangeTradeScheduler#tryBuy` → `RangeBuyRationale.describe(band.low(), band.high(),
  RangeTradeSignal.entryCeiling(band.low(), props), price)`.
- `RangeTradeScheduler#processHolding` → `sellRationale(p, reason, current)`(private)에서
  `RangeTradeSignal.profitFloor/breakdownFloor`를 재사용.

## 5. 테스트

| 테스트 | 건수 | 커버 |
|---|---|---|
| `BuyRationaleTest` | 9 | 저평가/촉매 경로, 인기 트리거 1·2개, 5/5 체크리스트, PER 결측, null 전체, 긴 제목 절삭 |
| `SellRationaleTest` | 5 | 트레일/하드/뉴스소멸 라벨과 바인딩 스탑, null 전체, 진입가 0 가드 |
| `RangeBuyRationaleTest` | 4 | 밴드·진입상한·밴드내 위치, 하단 0%, null 전체, 폭 0 가드 |
| `RangeSellRationaleTest` | 5 | 익절/이탈/악재 라벨과 임계선, null 전체, 진입가 0 가드 |
| `OrderExecutorTest`(추가 4) | — | message = `rationale \| outcome`, 주문실패에도 rationale 유지, 예산초과는 기존 문구, 500자 절삭 |
| `RangeOrderExecutorTest`(추가 4) | — | 동일 4건(레인지 트랙) |
| `AutoTradeSchedulerTest` | 3 | 매도·매수 호출에 전달된 rationale 문자열, 뉴스 조회 실패 시 `뉴스:N/A`로 매수 진행 |
| `RangeTradeSchedulerTest`(추가 2) | — | 매도/매수 호출에 전달된 rationale 문자열 |
| `PopularityCheckerTest`(추가 4) / `TrailingStopCalculatorTest`(추가 1) | — | 추출 수치가 boolean 판정·`decide` 경계와 일치 |
