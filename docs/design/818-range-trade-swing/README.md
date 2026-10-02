# 설계서: 레인지(박스권) 스윙매매 — 2번째 자동매매 트랙 (#818)

> **상태**: Approved
> **작성**: [AI] Architect · **최종수정**: 2026-10-01
> **추적성** — Redmine: #818 · 관련: #808(기존 모멘텀 자동매매 엔진), 관련 ADR: 없음
> · 구현 파일: `src/main/java/com/cloudhandson/tossstock/rangetrade/**`,
>   `src/main/resources/mapper/RangeTrade*.xml`, `src/main/resources/db/range_trade.sql`,
>   `DailyOhlcvMapper.rangeStatsBatch`(+XML), `UniverseMapper.findMarketBySymbol`(+XML),
>   `application.yml` `range-trade:` 섹션
> · 테스트: `src/test/java/com/cloudhandson/tossstock/rangetrade/**`
>   (`RangeBoundCheckerTest` 12건, `RangeTradeSignalTest` 10건, `RangeOrderExecutorTest` 11건,
>   `RangeTradeSchedulerTest` 6건 — 총 39건 신규, 전체 181건 통과)
> · 레퍼런스: `docs/reference/range-trade.md`

## 1. 목적 (Why)
#808의 모멘텀 엔진(뉴스호재+가격돌파로 사서 추세를 따라가는 전략)과 **반대 철학**의 2번째 트랙을 추가한다 — 추세 없이 일정 밴드 안에서 반복 왕복하는(박스권) 종목을, 밴드 하단에서 사서 상단에서 파는 평균회귀 스윙매매. 사용자가 대화 중 직접 게이트 순서를 제시했고(유동성→레인지확인→하단위치→악재없음→익절/손절), 이 설계서는 그걸 그대로 구현 가능한 형태로 구체화한다.

목표(1줄): "추세추종 전략이 못 먹는 횡보장에서도, 반복되는 박스권 패턴으로 별도 수익 기회를 만들되, 박스가 깨지면 반드시 손절한다."

## 2. 범위 (Scope)
- **포함**:
  - `RangeBoundChecker` — 종목이 실제로 추세 없이 박스권을 반복하는지 순수 판정.
  - `RangeTradeSignal` — 현재가 위치 기반 매수/익절매도/손절매도 순수 판정.
  - 강한 악재(S1/S2) 배제 게이트(#808 `NewsFadeDetector`의 반대 극성).
  - **예정된 실적발표 제외 게이트**(신규, `EarningsCalendarGate`) — 보유 예상기간 내 실적발표가 껴있는 종목은 후보에서 제외(§4 Ellman 방법론 참고).
  - 유동성 게이트(#808 `LiquidityChecker` 그대로 재사용).
  - 일 1회 배치 스캔(전 유니버스 대상, `DailyOhlcvMapper` 배치 쿼리 패턴 재사용) + `OrderExecutor`와 동일한 이중 드라이런 안전장치를 갖는 신규 `RangeOrderExecutor`.
  - 독립된 상태/포지션/후보 테이블 — #808 모멘텀 트랙과 예산·슬롯·로직 완전 분리.
  - KR 시장만(v1). US는 §12로 후속.
- **제외 (out of scope)**:
  - 옵션 기반 전략(커버드콜/캐시시큐어드풋) — **토스증권 Open API가 옵션 미지원이라 원천 불가능**(2026-10-01 실측 확인). 현물 매수/매도로만 구현.
  - 분봉 단위 정밀 타이밍 — 이건 스윙(일~주 단위 보유) 전략이라 #808처럼 1분 틱이 필요 없음, 일 1회 스캔으로 충분.
  - 밴드 자동 백테스트/파라미터 최적화 — 초기값은 합리적 추정치로 시작, "운영하면서 조정"(#808과 동일 원칙).

## 3. 인수조건 (Acceptance Criteria)
- [ ] 추세가 뚜렷한 종목(예: 최근 60일간 고점 대비 -30% 지속 하락한 종목)은 `RangeBoundChecker`에서 탈락한다.
- [ ] 박스권으로 판정된 종목이 현재가가 밴드 하단 근처(진입구간)일 때만 매수 신호가 난다.
- [ ] 보유 중 밴드 상단 근처 도달 시 익절 매도, **진입 시점 밴드 하단을 일정% 이상 이탈하면 손절 매도**(물타기 없이) — 이 두 조건은 서로 다른 `ExitReason`으로 구분된다.
- [ ] 활성 뉴스 중 S1 또는 S2(악재) 레벨이 있으면 신규 매수가 차단되고, 보유 중 새로 나타나면 가격과 무관하게 즉시 매도된다.
- [ ] `dry-run` 기본값 true, DB `range_trade_state.dry_run`과의 이중 안전장치 — #808 `OrderExecutor`와 동일한 불변식(둘 중 하나라도 true면 드라이런).
- [ ] 예산/슬롯이 #808 모멘텀 트랙과 완전히 분리되어 있어, 한쪽 서킷브레이커/슬롯 소진이 다른 쪽에 영향을 주지 않는다.

## 4. 컨텍스트 & 제약
- **옵션 미지원 확인(2026-10-01)**: 토스증권 Open API corp 문서/제3자 가이드 교차 확인 결과 지원 기능은 시세조회/계좌/일반주문/조건부주문뿐, 옵션(파생상품) 없음. 그래서 Dr. Alan Ellman 식 "풋 매도로 하단 매수당하고 콜 매도로 상단 매도당하는" 옵션 프리미엄 전략은 애초에 불가 — 현물로 같은 모양(하단매수/상단매도)만 흉내.
- **Ellman 방법론에서 차용한 2가지(2026-10-01, 사용자 지적)** — 옵션은 못 쓰지만 그의 "체계적 파라미터 설정 방식"은 유효함(실제 검증: thebluecollarinvestor.com):
  1. **ROO(Return on Option) 2~4%/월 — 목표수익률 역산 방식**: Ellman은 밴드/행사가를 감으로 정하지 않고 "수수료 뗀 순수익이 월 2~4% 나오는가"를 먼저 정하고 거꾸로 종목/가격을 고른다. 이 원칙을 `min-width-pct`에 적용 — §7/§12에서 임의 추정치(15%) 대신 목표수익률 역산값(~26%)으로 교체.
  2. **"Banned Stocks" — 예정된 실적발표 제외**: Ellman은 보유 예상기간 내 실적발표(그가 "위험한 월간 실적보고"라 부르는)가 껴있는 종목을 아예 후보에서 뺀다. 박스권이 아무리 예뻐도 실적 발표로 갭이 생기면 밴드가 순식간에 깨짐 — `EarningsCalendarGate`(신규)로 반영.
- **기존 재사용 가능 자산**:
  - `DailyOhlcvMapper.peakTroughBatch(pairs)` — 이미 종목별 `fromDate` 이후 고가/저가를 배치로 가져오는 쿼리가 존재(`HoldingService`의 MDD 계산용). 레인지 상/하단 계산에 거의 그대로 재사용 가능 — 단, "최신 종가"와 "추세 유무" 판정에 필요한 통계는 없어서 새 배치 쿼리 하나 추가 필요(§7).
  - `LiquidityChecker`, `NewsSignals`, `StockNewsMapper.active()` — #808 것 그대로.
  - `TossApiClient`(주문/시세) — 그대로.
  - `OrderExecutor`의 이중 드라이런 안전장치 **패턴**(코드 구조)은 그대로 복제하되, 아래 §11에서 설명하듯 **클래스 자체는 공유하지 않고 새로 만든다**(포지션 테이블이 다르기 때문).
- **제약**: 이 트랙은 "틀렸을 때 물타기하면 큰 손실"이라는 평균회귀 전략 고유의 리스크가 있음 — 손절 로직이 핵심 안전장치이고 리뷰 단계에서 특히 꼼꼼히 봐야 함(#808의 `OrderExecutor`와 동급 취급).
- **가정**: 사용자가 대화 중 제시한 게이트 순서(유동성→레인지확인→하단위치→악재없음→예외적으로 밴드폭 자체 체크→익절/손절)에 합의.

## 5. 아키텍처 개요
```
[RangeTradeScheduler] (@Scheduled, 1일 1회, 장마감 후 — 예: 0 0 16 * * MON-FRI)
  │
  ├─► [기존 보유 포지션 점검] (range_trade_position, status=HOLDING)
  │        ├─► 활성 뉴스 S1/S2 있음? → 있으면 즉시 BAD_NEWS 매도
  │        └─► RangeTradeSignal.decide(현재가, 진입시점 밴드하단, 밴드상단)
  │                 → PROFIT_TAKE / RANGE_BREAKDOWN 매도, 또는 NONE(유지)
  │
  ├─► [빈 슬롯 있으면 신규 후보 스캔]
  │        ├─► UniverseMapper.findAll()(KR) 중 유동성 통과 종목만
  │        ├─► DailyOhlcvMapper.rangeStatsBatch(windowDays) 일괄 조회(신규 쿼리)
  │        ├─► RangeBoundChecker.evaluate(각 종목 일봉) → 레인지 종목 + 밴드[저,고] 산출
  │        ├─► 현재가가 밴드 하단 근처(진입구간)인지
  │        ├─► 활성 뉴스 S1/S2 없음?
  │        └─► 통과 시 → RangeOrderExecutor.buy(...) — 진입시점 밴드하단 함께 기록
  │
  └─► [RangeOrderExecutor]
           ├─► dryRun=true  → range_trade_order_log 에 "의도"만 기록
           └─► dryRun=false → TossApiClient.placeOrder(...) 실호출 + range_trade_position 기록
```
- **I/O ↔ 순수 로직 경계**: `RangeBoundChecker.evaluate`, `RangeTradeSignal.decide`는 순수 함수(이미 조회된 일봉/가격을 받아 판정만). DB/HTTP는 `RangeTradeScheduler`/`RangeOrderExecutor`/`*Mapper`에 격리 — #808과 동일 원칙.
- **구현 보완(2026-10-01 Developer)**: 위 다이어그램의 "`UniverseMapper.findAll()`(KR) 중 유동성 통과 종목만"은 종목별 조회가 되어 3,700+ 종목에 N+1이 된다. 실제 구현은 `rangeStatsBatch`가 **`universe` 조인으로 KR 범위를 제한하며 쿼리 1번에 윈도우 통계를 집계**하고, 1차 스크리닝을 통과한 종목만 실제 일봉을 청크로 받아 `LiquidityChecker`·`RangeBoundChecker`로 최종 판정한다(깔때기 실측치는 §12).

## 6. 데이터 모델

### `range_trade_candidate` — **별도 테이블 불필요**(결정사항)
#808은 뉴스로 발견된 종목을 `auto_trade_candidate`에 큐레이션하지만, 이 트랙은 뉴스 트리거가 아니라 "매일 전 유니버스를 스캔해서 패턴을 찾는" 방식이라 후보 큐레이션 테이블 자체가 불필요 — 매 스캔마다 유니버스 전체(유동성 통과분)를 대상으로 `RangeBoundChecker`를 돌린다.

### `range_trade_position` (신규, #808 `auto_trade_position`과 분리)
| 컬럼 | 타입 | 설명 |
|---|---|---|
| id | NUMBER IDENTITY PK | |
| symbol | VARCHAR2(20) | |
| market | VARCHAR2(10) | v1은 'KR'만 |
| status | VARCHAR2(20) | HOLDING / EXITED |
| entry_price | NUMBER | |
| entry_qty | NUMBER | |
| entry_at | TIMESTAMP | |
| range_low_at_entry | NUMBER | **진입 시점** 밴드 하단(고정값 — 트레일링 아님, §9 참고) |
| range_high_at_entry | NUMBER | 진입 시점 밴드 상단(목표 익절가 산출용) |
| budget_allocated | NUMBER | |
| exit_price | NUMBER | |
| exit_reason | VARCHAR2(30) | PROFIT_TAKE / RANGE_BREAKDOWN / BAD_NEWS / MANUAL |
| exit_at | TIMESTAMP | |
| dry_run | NUMBER(1) | |
| created_at | TIMESTAMP DEFAULT CURRENT_TIMESTAMP | |

### `range_trade_state` (싱글턴, #808 `auto_trade_state`와 분리 — 독립 예산/서킷브레이커)
| 컬럼 | 설명 |
|---|---|
| total_budget, max_symbols, dry_run, circuit_breaker_tripped 등 | `auto_trade_state`와 동일 구조, 완전히 별도 행/테이블. **total_budget=1,000,000**(2026-10-01 확정, §11). **max_symbols=2, per_symbol_budget=500,000**(2026-10-01 Developer 확정, §12). **circuit-breaker-pct=15.0**(2026-10-02 추가 — #808과 동일 기준 재사용, §12). |

### `range_trade_order_log` (#808 `auto_trade_order_log`와 동일 구조, 분리된 테이블)
- 2026-10-01 #808 사고(toss_order_id 길이초과로 포지션 유실) 교훈 반영 — **처음부터 `toss_order_id VARCHAR2(200)`으로 생성**.

## 7. 함수 명세 (Function Specs)

| 함수 | 책임(1줄) | 시그니처(잠정) | 입력 | 출력 | 에러/실패 | 복잡? |
|------|-----------|----------------|------|------|-----------|-------|
| `RangeBoundChecker.evaluate` | 종목이 추세 없이 박스권을 반복하는지 + 밴드[저,고] 산출 | `Result evaluate(List<DailyOhlcv> window, RangeTradeProperties props)` | 일봉 리스트(window-days치) | `Result(isRangeBound, low, high)` | 데이터 부족 시 `isRangeBound=false` | **복잡** → `fn-range-bound-checker.md` |
| `RangeTradeSignal.decide` | 매수/익절/손절 순수 판정 | `Signal decide(BigDecimal current, BigDecimal rangeLowAtEntry, BigDecimal rangeHighAtEntry, boolean holding, RangeTradeProperties props)` (구현 확정 시그니처 — fn 설계서 §1과 동일, 이 표에서 `holding`이 빠져 있었음) | 현재가, 진입시점 밴드, 보유여부 | `Signal(BUY\|PROFIT_TAKE\|RANGE_BREAKDOWN\|NONE)` | 입력값 이상(음수 등) → 예외 | **복잡** → `fn-range-trade-signal.md` |
| `BadNewsGate.hasStrongBadNews` | 활성 뉴스 중 S1/S2(악재) 존재 여부 | `boolean hasStrongBadNews(String symbol)` | symbol | boolean | **2026-10-01 구현 확정: 조회 실패 시 `false`(차단 안 함, fail-open)** — §9 추천안 채택 | 단순(`NewsFadeDetector`와 동일 패턴의 반대 극성) |
| `EarningsCalendarGate.hasUpcomingEarnings` | 예상 보유기간 내 실적발표 예정 여부(Ellman "Banned Stocks") | `boolean hasUpcomingEarnings(String symbol, int holdingHorizonDays)` | symbol, 보유예상기간 | boolean | **2026-10-01 실측으로 확정: 야후 `calendarEvents`(KR은 `.KS`/`.KQ` 서픽스) 사용, 데이터 없음/실패 시 통과(fail-open)** — 네이버엔 쓸 수 있는 필드 없음(§12) | 단순 |
| `RangeOrderExecutor.buy`/`.sell` | 드라이런 분기 포함 주문 실행(포지션은 `range_trade_position`) | `boolean buy(String symbol, BigDecimal budget, BigDecimal currentPrice, BigDecimal rangeLow, BigDecimal rangeHigh)` 등 | - | boolean | 주문 실패 재시도 안 함(#808과 동일 원칙) | **복잡** → #808 `fn-order-executor.md`의 패턴을 그대로 참고해 구현하되 별도 설계서는 생략(동일 구조 반복 — 구현 시 그 문서를 "모델"로 명시) |
| `RangeTradeScheduler.tick` | 한 틱(1일 1회)의 오케스트레이션 | `void tick()` | - | - | 개별 종목 예외는 해당 종목만 skip(전체 틱 안 죽도록 — #808에서 발견된 "한 종목 예외가 전체 틱을 죽임" 문제 재발 방지, §9) | 단순(호출만) |

> 복잡 기준은 `_TEMPLATE.md` 그대로(분기/상태기계, 외부 I/O, 리스크 경로, 비자명 알고리즘).

## 8. 흐름 / 알고리즘
1. `@Scheduled` 1일 1회(장마감 후, 예: `0 0 16 * * MON-FRI` — `DailyCollector`의 일봉 갱신 15:40 이후로 버퍼를 둠).
2. `range_trade_state` 로드. 서킷브레이커 트립 시 매도만 수행(#808과 동일 원칙).
3. 보유 포지션(`HOLDING`) 각각: `BadNewsGate` 먼저 체크(S1/S2 있으면 즉시 `BAD_NEWS` 매도) → 없으면 `RangeTradeSignal.decide`로 `PROFIT_TAKE`/`RANGE_BREAKDOWN` 판정 → 매도 시 `RangeOrderExecutor.sell`.
4. 빈 슬롯 있으면: `UniverseMapper.findAll()`(KR) → 유동성 통과 종목만 → `DailyOhlcvMapper.rangeStatsBatch(windowDays)`로 일괄 조회 → 종목별 `RangeBoundChecker.evaluate` → 레인지+하단위치 통과 + `BadNewsGate` 통과 + **`EarningsCalendarGate` 통과(예상 보유기간 내 실적발표 없음)** 종목 → `RangeOrderExecutor.buy`(이때 `range_low_at_entry`/`range_high_at_entry`를 **그 시점 값으로 고정 저장** — 이후 가격이 올라가도 바뀌지 않음, §9).
5. 모든 신호(스킵 포함) 로그 — #808에서 "설계서엔 다 기록한다 했는데 실제로 스킵 경로는 로그 안 남기는" 불일치가 있었음(README §11 참고) — 이 트랙은 처음부터 일치시킬지 결정 필요(§12).

## 9. 엣지케이스 & 에러 처리
- **밴드 하단은 "진입 시점 고정값"이지 트레일링이 아님**: #808의 `peak_price`는 보유 중 계속 갱신(위로)되지만, 이 트랙의 `range_low_at_entry`는 **진입 시점에 한 번 고정**하고 이후 갱신 안 함. 이유: 레인지 전략은 "그 밴드가 깨지면 손절"이 핵심인데, 하단을 계속 갱신(새 저점 추종)하면 밴드가 무한정 아래로 늘어나면서 "손절을 영원히 피하는" 함정이 됨 — 이게 바로 §11에서 경고한 "물타기 정당화" 실패 패턴. **절대 트레일링하면 안 된다.**
- **한 종목 처리 중 예외가 전체 틱을 죽이면 안 됨**: #808 실사례(2026-10-01, `getPrices` 429/`saveLog` ORA-12899가 전체 `tick()`을 중단시킴)를 교훈 삼아, `RangeTradeScheduler`는 종목 단위로 try/catch — 한 종목 실패가 나머지 스캔을 막지 않게.
- **`BadNewsGate` 조회 실패 시 기본값**: 모멘텀 트랙과 반대로 "정보 없음=안전"이 아니라 "정보 없음=차단 안 함(신규매수 허용)"인지 "차단(매수 금지)"인지 정해야 함 — 추천은 **차단하지 않음**(API 일시 장애로 영원히 매수 못 하게 되는 건 과함), 단 보유 중 매도 판단에서는 조회 실패 시 매도하지 않음(기존 포지션 유지가 기본, 손절은 가격 기반 로직이 여전히 작동하므로 과도한 무방비는 아님).
- **진입 직후 바로 손절 트리거되는 경우**: 밴드 하단 10% 이내에서 샀는데 바로 손절선(하단 -5%)에 걸리면 사실상 진입폭이 5%도 안 됨 — `entry-zone-pct`와 `breakdown-pct`가 겹치지 않게 파라미터 관계를 검증해야 함(테스트 케이스에 포함).
- **데이터 부족(상장 60일 미만 등)**: `RangeBoundChecker`가 `isRangeBound=false`로 fail-closed(#808 전반의 원칙과 동일).

## 10. 테스트 계획
- `RangeBoundCheckerTest`: 명확한 추세 종목(지속 상승/하락) → false, 반복 왕복 패턴(인위적 데이터) → true + 정확한 밴드값, 데이터 부족 → false.
- `RangeTradeSignalTest`: 하단 근처 → BUY, 상단 근처 보유중 → PROFIT_TAKE, 진입시점 하단 -5% 이탈 → RANGE_BREAKDOWN, 밴드 중간 → NONE, 경계값(정확히 임계선).
- `RangeOrderExecutorTest`: #808 `OrderExecutorTest`와 동일한 안전 테스트 세트(dryRun 이중 안전장치, 예산 초과 차단) — **감사로그 실패해도 포지션 기록은 진행**(#808에서 바로 고친 그 패턴을 처음부터 반영, §11).
- `RangeTradeSchedulerTest`(2026-10-01 Developer 추가, 6건): 틱 오케스트레이션 내구성 — `range_trade_state` 없음→무동작, **보유 종목 1건 처리 중 예외가 다른 종목 점검과 신규 스캔을 막지 않음**(§9 재발 방지 조건을 테스트로 고정), 악재 즉시매도, 하단이탈 손절, 서킷브레이커 트립 시 매도만 수행, 빈 슬롯 없으면 스캔 생략.
- 통합: 실주문 API 호출 테스트는 작성 안 함(#808과 동일 원칙).

## 11. 리스크 & 대안 검토
- **포지션 테이블을 공유할지 분리할지**: `auto_trade_position`에 `strategy` 구분 컬럼만 추가하는 방안도 검토했으나 **기각** — 두 전략의 매도 판정 로직(트레일링-from-peak vs 고정밴드이탈)이 근본적으로 달라서 같은 테이블/스케줄러에 섞으면 분기 로직이 뒤엉키고, #808 서킷브레이커가 레인지 전략 손익까지 같이 계산해버리는 부작용이 생김. 완전 분리를 선택.
- **`OrderExecutor` 클래스를 공유할지**: 공유하려면 포지션 리포지토리를 추상화해야 하는데, 이미 실거래 검증된 #808 `OrderExecutor`를 건드리는 리스크가 크다고 판단 — **새 `RangeOrderExecutor`를 만들되 동일한 이중 드라이런 안전장치 구조를 그대로 복제**하는 쪽으로 결정. `TossApiClient`(주문 API 클라이언트 자체)는 그대로 공유.
- **왜 1분 틱이 아니라 1일 1회인가**: 이건 스윙(며칠~몇 주 보유) 전략이라 분 단위 타이밍이 의미가 적고, 전 유니버스(3700+ 종목) 스캔을 1분마다 돌리는 건 DB/API 부담만 키움. 다만 "밴드 하단까지 -5% 이탈"처럼 급락이 하루 안에 다 벌어질 수 있는 손절 조건은 일 1회로는 늦게 반응할 수 있음(최대 하루 지연) — 이건 트레이드오프로 인지하고 감수(운영하며 재검토).
- **옵션 전략(Ellman 방식) 포기**: 토스증권이 옵션을 지원하지 않는 한 선택지가 없음 — 다른 브로커로 갈아타는 건 이 이슈 범위 밖.

## 12. 미해결 질문 (Open Questions)
- **밴드 정의 파라미터 — `min-width-pct`는 2026-10-01 Ellman 방식(ROO 목표수익률 역산)으로 재계산, 나머지는 여전히 초기 추정치**:
  - `window-days` = 60(초기 제안, 확정 아님)
  - **`min-width-pct` = 15.0 → 약 26.0으로 상향(역산)**: 왕복비용(#808 195870 실측: 60,000원 2주 왕복 136원 ≈ **0.23%**)에 Ellman의 월 2~4% ROO 관례를 참고한 목표 순수익 **3%**를 더한 3.25%가 "최악의 경우(진입구간 꼭대기에서 사서 익절구간 바닥에서 팜)"에도 남으려면, `entry-zone-pct`/`exit-zone-pct`=10/10 기준 밴드폭이 최소 **~26%**는 돼야 함(`worstCase = [(1+w)*0.9 - 1.1]/1.1 >= 0.0325` → `w >= 0.262`). **목표수익률(3%)은 여전히 가정값** — 사용자 확인 필요.
  - `max-width-pct` = 50.0(초기 제안, 그대로 유지)
  - `max-trend-drift-pct` = 15.0(초기 제안, 그대로)
  - `entry-zone-pct`/`exit-zone-pct` = 10.0/10.0(초기 제안 — 위 역산의 전제값, 바뀌면 min-width-pct도 재계산 필요)
  - `breakdown-pct` = 5.0(초기 제안, 그대로)
- ~~**`EarningsCalendarGate`의 데이터 소스 미정(신규)**~~ → **2026-10-01 Developer 단계 실측으로 해소**. Ellman의 "Banned Stocks"(예정된 실적발표 제외)를 적용하려면 "다음 실적발표 예정일"이 필요한데, 실제 API를 호출해 확인한 결과:
  - **네이버(KR) — 사용 불가**: `m.stock.naver.com/api/stock/{code}/integration` 응답에 `irScheduleInfo` 필드가 **존재하지만** 실측 10종목(005930·000660·035420·005380·012330·051910·068270·207940·035720·105560) 전부 `null`. `shareholdersMeetingInfo`도 전부 `null`. KR 실적일 소스로 못 씀.
  - **야후 — 사용 가능(KR 포함)**: `query1.finance.yahoo.com/v10/finance/quoteSummary/{ticker}?modules=calendarEvents` 가 `calendarEvents.earnings.earningsDate[].fmt` 로 다음 실적발표일을 돌려줌. **국내 종목도 서픽스를 붙이면 됨**(실측: `005930.KS`=2026-10-28, `035720.KS`=2026-11-06, `000660.KS`=2026-10-27(추정), `247540.KQ`=2026-10-30(추정), `AAPL`=2026-10-29). 단 `058470.KQ` 처럼 `earningsDate`가 빈 배열인 종목도 있고, `isEarningsDateEstimate=true`(추정치)인 경우가 흔함.
  - **구현 결정**: 야후 `calendarEvents` 를 소스로 사용(서픽스는 `universe.market`으로 KOSDAQ→`.KQ`, 그 외→`.KS`. 이를 위해 `UniverseMapper.findMarketBySymbol` 추가). **데이터 없음/조회 실패 시에는 차단하지 않고 통과(fail-open)** — 억지로 다른 소스를 만들지 않음. 반대로 **추정치(`isEarningsDateEstimate=true`)는 보수적으로 "예정 있음"으로 취급해 차단**한다(스킵의 비용은 기회비용뿐, 실적 갭의 비용은 실손실이라 비대칭).
  - **예상 보유기간**: `holding-horizon-days=30`(Ellman의 월간 옵션 주기 차용 — **가정값**).
- ~~예산/슬롯 수~~ → **2026-10-01 해소**: 기존 400만원 예산을 모멘텀(#808) 300만/레인지(#818) 100만으로 재분배(사용자 결정). `range_trade_state.total_budget=1,000,000`으로 구현. 슬롯 수는 Developer 단계에서 per-symbol 예산 설계와 함께 확정(예: 2~3슬롯×33~50만원). 모멘텀 쪽은 `auto-trade.total-budget`을 4,000,000→3,000,000, `per-symbol-budget`을 800,000→600,000으로 즉시 반영·배포 완료(DB `auto_trade_state`도 갱신). 단, 현재 012330 포지션에 377만원이 묶여있어(매수가능금액 22만원뿐) 당장은 숫자상 재분배일 뿐 — 포지션 매도로 현금이 풀려야 레인지 트랙에 실제 자금이 들어감.
  - **슬롯 수 확정(2026-10-01 Developer)**: `max-symbols=2`, `per-symbol-budget=500,000`. 3슬롯(33만원)은 1종목당 금액이 작아져 10만원대 주가 종목이 3주밖에 안 되고 정수 주 반올림 버림으로 예산의 최대 1/3이 유휴가 됨. 목표 순수익 3%가 50만원 기준 1.5만원/회로 유의미한 크기이고, 1슬롯은 분산이 0이라 제외 → 2슬롯.
  - **유동성 임계값(2026-10-01 Developer)**: `min-avg-trading-value=300,000,000`(#808과 동일 기준 — `LiquidityChecker`를 그대로 재사용하므로 기준도 같게 시작).
- ~~**스캔 대상 유니버스 필터링 강도**~~ → **2026-10-01 Developer 단계 실측으로 해소**. 2026-10-01 기준 실제 DB로 측정한 깔때기(윈도우 60거래일):
  | 단계 | 종목 수 |
  |---|---|
  | KR 유니버스 중 일봉 61개 이상 | 3,709 |
  | 유동성 통과(평균 거래대금 ≥ 3억) | 2,130 |
  | 밴드폭 26~50% | 786 |
  | + 추세 드리프트 ≤ 15% | 742 |
  | + 최신 종가가 진입구간(하단+10%) 안 | 133 |
  | (유동성 필터 없이 폭+드리프트+진입구간만) | 279 |

  구현은 **2단계**로 간다: ① `DailyOhlcvMapper.rangeStatsBatch`(쿼리 1번)로 전 종목 윈도우 통계를 받아 Java에서 느슨한 1차 스크리닝(≈279종목) → ② 살아남은 종목만 실제 일봉을 청크(≤900종목/쿼리)로 받아 `LiquidityChecker`+`RangeBoundChecker.evaluate`로 **최종 판정**. 1차 스크리닝은 성능용이고 판정 권한이 없다 — 두 경로가 어긋나면 후보가 누락될 뿐(false negative) 잘못된 매수로는 이어지지 않는다.
- **신규 후보의 "현재가" 소스 결정(2026-10-01 Developer, 2026-10-02 Architect 검토 완료)** — 보유 포지션(최대 2종목)은 `PriceCache`(실시간 시세)를 쓰지만, **신규 후보는 윈도우의 최신 종가**를 현재가로 쓴다. 이유: ① 장마감(15:30) 후 16:00 실행이라 최신 종가=30분 전 현재가, 스윙(일~주 보유) 전략엔 무시할 수 있는 오차, ② 밴드와 가격을 같은 일봉 스냅샷에서 뽑아 내부 일관성 유지, ③ 후보 수백 종목에 시세 API를 때리면 **실거래 중인 #808 모멘텀 엔진과 같은 토스 API 레이트리밋(429 실사례 있음)을 건드릴 수 있음**. QA 검토 결과 수정 불필요 — 그대로 채택.
- **미국 시장 확장 여부** — v1은 KR만, US는 #808처럼 후속 이슈로 둘지 같이 설계할지.
- ~~**스킵 경로 로깅 수준**~~ → **2026-10-01 Developer 결정**: 스캔 단계의 대량 스킵(일봉 부족/유동성/박스권 아님/진입구간 아님)은 **DEBUG**, 최종 단계까지 올라온 후보가 게이트(악재·실적발표)로 막힌 건은 **INFO**, 보유 포지션 판정 결과(유지/매도)는 **INFO**, 주문 시도는 `range_trade_order_log`에 감사 기록. 수백 종목 × 매일을 INFO로 남기면 로그가 쓸모없어지므로 "전부 INFO"는 채택하지 않음 — 설계서와 코드를 이 수준으로 일치시킨다.
- ~~서킷브레이커 자동 트립은 v1 미구현~~ → **2026-10-02 해소**: #808 `CircuitBreaker.check`(순수함수, `currentEquity/initialBudget/thresholdPct`만 받음)를 그대로 재사용 — 전략 특화 로직이 전혀 없어 새로 만들 필요가 없었음. `RangeTradeScheduler`가 매 틱 `range_trade_position` 전체(보유+청산)로 평가손익을 계산해 `circuit-breaker-pct=15.0`(#808과 동일 기준) 도달 시 자동 트립 + Discord 알림. 테스트(`fresh_equity_breach_trips_circuit_breaker_and_skips_scan`/`healthy_equity_does_not_trip`)로 고정.
