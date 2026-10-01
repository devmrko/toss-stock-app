# 설계서: 레인지(박스권) 스윙매매 — 2번째 자동매매 트랙 (#818)

> **상태**: Draft
> **작성**: [AI] Architect · **최종수정**: 2026-10-01
> **추적성** — Redmine: #818 · 관련: #808(기존 모멘텀 자동매매 엔진), 관련 ADR: 없음
> · 구현 파일(예정): `src/main/java/com/cloudhandson/tossstock/rangetrade/**`
> · 테스트(예정): `src/test/java/com/cloudhandson/tossstock/rangetrade/**`

## 1. 목적 (Why)
#808의 모멘텀 엔진(뉴스호재+가격돌파로 사서 추세를 따라가는 전략)과 **반대 철학**의 2번째 트랙을 추가한다 — 추세 없이 일정 밴드 안에서 반복 왕복하는(박스권) 종목을, 밴드 하단에서 사서 상단에서 파는 평균회귀 스윙매매. 사용자가 대화 중 직접 게이트 순서를 제시했고(유동성→레인지확인→하단위치→악재없음→익절/손절), 이 설계서는 그걸 그대로 구현 가능한 형태로 구체화한다.

목표(1줄): "추세추종 전략이 못 먹는 횡보장에서도, 반복되는 박스권 패턴으로 별도 수익 기회를 만들되, 박스가 깨지면 반드시 손절한다."

## 2. 범위 (Scope)
- **포함**:
  - `RangeBoundChecker` — 종목이 실제로 추세 없이 박스권을 반복하는지 순수 판정.
  - `RangeTradeSignal` — 현재가 위치 기반 매수/익절매도/손절매도 순수 판정.
  - 강한 악재(S1/S2) 배제 게이트(#808 `NewsFadeDetector`의 반대 극성).
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
| total_budget, max_symbols, dry_run, circuit_breaker_tripped 등 | `auto_trade_state`와 동일 구조, 완전히 별도 행/테이블. 예산 액수는 §12 미해결 질문. |

### `range_trade_order_log` (#808 `auto_trade_order_log`와 동일 구조, 분리된 테이블)
- 2026-10-01 #808 사고(toss_order_id 길이초과로 포지션 유실) 교훈 반영 — **처음부터 `toss_order_id VARCHAR2(200)`으로 생성**.

## 7. 함수 명세 (Function Specs)

| 함수 | 책임(1줄) | 시그니처(잠정) | 입력 | 출력 | 에러/실패 | 복잡? |
|------|-----------|----------------|------|------|-----------|-------|
| `RangeBoundChecker.evaluate` | 종목이 추세 없이 박스권을 반복하는지 + 밴드[저,고] 산출 | `Result evaluate(List<DailyOhlcv> window, RangeTradeProperties props)` | 일봉 리스트(window-days치) | `Result(isRangeBound, low, high)` | 데이터 부족 시 `isRangeBound=false` | **복잡** → `fn-range-bound-checker.md` |
| `RangeTradeSignal.decide` | 매수/익절/손절 순수 판정 | `Signal decide(BigDecimal current, BigDecimal rangeLowAtEntry, BigDecimal rangeHighAtEntry, RangeTradeProperties props)` | 현재가, 진입시점 밴드 | `Signal(BUY\|PROFIT_TAKE\|RANGE_BREAKDOWN\|NONE)` | 입력값 이상(음수 등) → 예외 | **복잡** → `fn-range-trade-signal.md` |
| `BadNewsGate.hasStrongBadNews` | 활성 뉴스 중 S1/S2(악재) 존재 여부 | `boolean hasStrongBadNews(String symbol)` | symbol | boolean | 조회 실패 시 안전 쪽(true=차단)? → §9 결정 필요 | 단순(`NewsFadeDetector`와 동일 패턴의 반대 극성) |
| `RangeOrderExecutor.buy`/`.sell` | 드라이런 분기 포함 주문 실행(포지션은 `range_trade_position`) | `boolean buy(String symbol, BigDecimal budget, BigDecimal currentPrice, BigDecimal rangeLow, BigDecimal rangeHigh)` 등 | - | boolean | 주문 실패 재시도 안 함(#808과 동일 원칙) | **복잡** → #808 `fn-order-executor.md`의 패턴을 그대로 참고해 구현하되 별도 설계서는 생략(동일 구조 반복 — 구현 시 그 문서를 "모델"로 명시) |
| `RangeTradeScheduler.tick` | 한 틱(1일 1회)의 오케스트레이션 | `void tick()` | - | - | 개별 종목 예외는 해당 종목만 skip(전체 틱 안 죽도록 — #808에서 발견된 "한 종목 예외가 전체 틱을 죽임" 문제 재발 방지, §9) | 단순(호출만) |

> 복잡 기준은 `_TEMPLATE.md` 그대로(분기/상태기계, 외부 I/O, 리스크 경로, 비자명 알고리즘).

## 8. 흐름 / 알고리즘
1. `@Scheduled` 1일 1회(장마감 후, 예: `0 0 16 * * MON-FRI` — `DailyCollector`의 일봉 갱신 15:40 이후로 버퍼를 둠).
2. `range_trade_state` 로드. 서킷브레이커 트립 시 매도만 수행(#808과 동일 원칙).
3. 보유 포지션(`HOLDING`) 각각: `BadNewsGate` 먼저 체크(S1/S2 있으면 즉시 `BAD_NEWS` 매도) → 없으면 `RangeTradeSignal.decide`로 `PROFIT_TAKE`/`RANGE_BREAKDOWN` 판정 → 매도 시 `RangeOrderExecutor.sell`.
4. 빈 슬롯 있으면: `UniverseMapper.findAll()`(KR) → 유동성 통과 종목만 → `DailyOhlcvMapper.rangeStatsBatch(windowDays)`로 일괄 조회 → 종목별 `RangeBoundChecker.evaluate` → 레인지+하단위치 통과 + `BadNewsGate` 통과 종목 → `RangeOrderExecutor.buy`(이때 `range_low_at_entry`/`range_high_at_entry`를 **그 시점 값으로 고정 저장** — 이후 가격이 올라가도 바뀌지 않음, §9).
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
- 통합: 실주문 API 호출 테스트는 작성 안 함(#808과 동일 원칙).

## 11. 리스크 & 대안 검토
- **포지션 테이블을 공유할지 분리할지**: `auto_trade_position`에 `strategy` 구분 컬럼만 추가하는 방안도 검토했으나 **기각** — 두 전략의 매도 판정 로직(트레일링-from-peak vs 고정밴드이탈)이 근본적으로 달라서 같은 테이블/스케줄러에 섞으면 분기 로직이 뒤엉키고, #808 서킷브레이커가 레인지 전략 손익까지 같이 계산해버리는 부작용이 생김. 완전 분리를 선택.
- **`OrderExecutor` 클래스를 공유할지**: 공유하려면 포지션 리포지토리를 추상화해야 하는데, 이미 실거래 검증된 #808 `OrderExecutor`를 건드리는 리스크가 크다고 판단 — **새 `RangeOrderExecutor`를 만들되 동일한 이중 드라이런 안전장치 구조를 그대로 복제**하는 쪽으로 결정. `TossApiClient`(주문 API 클라이언트 자체)는 그대로 공유.
- **왜 1분 틱이 아니라 1일 1회인가**: 이건 스윙(며칠~몇 주 보유) 전략이라 분 단위 타이밍이 의미가 적고, 전 유니버스(3700+ 종목) 스캔을 1분마다 돌리는 건 DB/API 부담만 키움. 다만 "밴드 하단까지 -5% 이탈"처럼 급락이 하루 안에 다 벌어질 수 있는 손절 조건은 일 1회로는 늦게 반응할 수 있음(최대 하루 지연) — 이건 트레이드오프로 인지하고 감수(운영하며 재검토).
- **옵션 전략(Ellman 방식) 포기**: 토스증권이 옵션을 지원하지 않는 한 선택지가 없음 — 다른 브로커로 갈아타는 건 이 이슈 범위 밖.

## 12. 미해결 질문 (Open Questions)
- **밴드 정의 파라미터 확정값 없음** — 아래는 초기 추정치, 실측/백테스트 전까지 확정 아님:
  - `window-days` = 60(초기 제안)
  - `min-width-pct`/`max-width-pct` = 15.0/50.0(초기 제안 — 너무 좁으면 거래비용 대비 안 남고, 너무 넓으면 진짜 박스권이 아닐 가능성)
  - `max-trend-drift-pct` = 15.0(전반부/후반부 평균 종가 차이로 추세 유무 판정, 초기 제안)
  - `entry-zone-pct`/`exit-zone-pct` = 10.0/10.0(밴드 하단/상단 근접 기준)
  - `breakdown-pct` = 5.0(진입시점 하단 대비 추가 이탈폭 — 손절)
- **예산/슬롯 수** — #808처럼 실제 계좌 상황 보고 사용자가 정해야 함. 완전히 별도 풀로 둘지, 전체 예산 안에서 모멘텀과 나눠 쓸지도 미정.
- **스캔 대상 유니버스 필터링 강도** — 3700+ 종목 전부 매일 `RangeBoundChecker` 돌리면 연산량이 꽤 됨, 유동성 1차 필터로 얼마나 줄어드는지 실측 필요.
- **미국 시장 확장 여부** — v1은 KR만, US는 #808처럼 후속 이슈로 둘지 같이 설계할지.
- **스킵 경로 로깅 수준** — #808에서 "설계서는 전부 로그 남긴다 했는데 코드는 안 남김" 불일치가 있었음(README §11) — 이 트랙은 처음부터 일치시킬지, 아니면 동일하게 성공/실패만 남길지 결정 필요.
