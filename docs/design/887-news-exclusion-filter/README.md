# 설계서: 뉴스를 진입 트리거에서 배제 필터로 전환 + 원칙 §3 기반 후보 스크리너 (#887)

> **상태**: Draft
> **작성**: [AI] Architect · **최종수정**: 2026-10-09
> **추적성** — Redmine: #887 · 관련 ADR: 없음(ADR 후보 — §13 참조)
> · 근거 측정: `docs/analysis/2026-10-09-news-trigger-negative-expectancy.md`
> · 구현 파일: §5 목록 · 테스트: §10 목록

## 1. 목적 (Why)

> 뉴스를 유일한 진입 트리거로 쓰는 구조가 음수 기대값으로 측정됐다 — 뉴스를 **배제 필터로만**
> 쓰고, 후보 발굴을 **원칙 §3 기반 스크리너**로 대체한다.

KOSPI 가 +183.6% 오른 기간(2025-01~2026-10)에 뉴스일 진입의 실현손익은 **−0.11%**(수수료 전,
N=2,149)였다. 같은 종목의 뉴스 없는 날은 +7.71%, 지수 보유는 +5.70% 였다.
급등률을 통제해도 전 구간에서 4.6~8.6%p 열등하므로 `EXTENDED` 게이트로는 고칠 수 없고,
뉴스 후 10거래일을 기다려도 수수료 후 +0.59% 로 해결되지 않는다.

또한 `docs/reference/investment-principles.md` §3 은 7개 항목 체크리스트이며 **뉴스는 1번 항목의
'구체 촉매'에만** 기여한다. 원칙은 뉴스를 주된 진입 근거로 쓰지 않는데 봇은 유일한 트리거로 썼다.

## 2. 범위 (Scope)

- **포함**
  - 뉴스 기반 후보 발굴(`addNewCandidates` / `removeFadedCandidates`) 제거
  - 로컬 `daily_ohlcv` 기반 `UniverseScreener` 신설 (전 종목 → 상위 N)
  - 뉴스 리스크 이벤트 기반 **배제** 목록(`NewsRiskExclusion`) 신설
  - 매수측 `NEWS_FADED` / `FADE_COOLDOWN` 게이트 제거 (§4 제약 참조 — 제거 없이는 동작 불가)
  - `max-extension-pct` 6.0 → 3.0, 촉매면제(`catalystAllowed`)에 급등률 상한 신설
  - `ValuationClient.getAnnualFinancials` 캐시 신설 (선행조건 — §4)
- **제외 (out of scope)**
  - **US 시장 활성화** — `daily_ohlcv` 의 US 거래량이 깨져 있어(#885, 최신/20일평균 중위 0.125배)
    거래대금 기반 유동성 필터가 US 를 전멸시킨다. 게다가 환전 미적용(#886)으로 US 매수 경로
    자체가 깨져 있다. 스크리너는 **KR 만 활성화**하고 US 는 #886 → #885 해결 후 별도 이슈로 켠다.
  - `POPULARITY` 게이트 제거 — 제거를 정당화하는 측정이 없다(§13-3).
  - `RiskEventDetector`(#872) 보유 중 매도 로직 변경 — 그대로 유지한다.
  - 새 랭킹 신호(모멘텀·수급 등) 발굴 — 측정된 알파가 없다(§13-1).

## 3. 인수조건 (Acceptance Criteria)

- [ ] **AC1** 뉴스 기사만으로는 어떤 종목도 `auto_trade_candidate` 에 등록되지 않는다.
- [ ] **AC2** 최근 `risk-exclusion-days` 일 내 리스크 이벤트 기사가 있는 종목은 스크리너
      통과분에서도 제외된다.
- [ ] **AC3** `RiskEventDetector` 기반 보유 중 매도가 기존대로 동작한다(회귀 금지).
- [ ] **AC4** 1회 발굴 사이클이 등록하는 후보 수가 `screen-top-n`(기본 40) 이하로 묶인다.
- [ ] **AC5** 매수측 `NEWS_FADED` / `FADE_COOLDOWN` 탈락이 더 이상 발생하지 않는다.
- [ ] **AC6** `max-extension-pct` 가 3.0 으로 적용되고, 급등률이
      `catalyst-max-extension-pct`(기본 15.0) 이상이면 촉매면제가 적용되지 않는다.
- [ ] **AC7** `getAnnualFinancials` 가 동일 (종목, 시장)에 대해 TTL 내 재호출 시 외부 요청을
      보내지 않는다.
- [ ] **AC8** 스크리너 각 단계의 잔존/탈락 수가 로그 1줄로 관측된다(#884 `publishScan` 수준).
- [ ] **AC9** 구 게이트로 등록된 활성 후보(노트 접두사 `자동발견(`)가 1회 발굴 사이클 안에
      모두 비활성화된다.
- [ ] **AC10** 수동 등록 후보(노트 접두사가 `자동발견(`·`스크리너(` 둘 다 아님)는 건드리지 않는다.

## 4. 컨텍스트 & 제약

- **의존성**: `daily_ohlcv`(로컬, Oracle) · `stock_news`(로컬) · 네이버/야후 재무 API ·
  Toss 시세 API · `auto_trade_candidate`.
- **레이트리밋(결정적 제약)**: `fundamentalScore()` 는 종목당 `getAnnualFinancials` 1회를
  호출하고 **이 메서드엔 캐시가 없다**(`ValuationClient:128` — `getValuation` 만 15분 캐시).
  `tick()` 은 1분 주기(`0 * * * * *`)이므로 후보 40개가 모두 `POPULARITY` 를 통과하면
  분당 40회 외부 요청이 된다. HTTP 429 전례가 있다. → **`getAnnualFinancials` 캐시가 선행조건.**
  - 실측 완충: 스크리너 상위 40 중 `POPULARITY` 통과는 최근 10거래일 **3~17건(중위 8건)**.
    `tick()` 의 게이트 순서상 `POPULARITY` 가 밸류에이션·재무 API 보다 **앞**이므로
    실제 API 대상은 중위 8종목이다.
- **동작 불가 제약(발견)**: 스크리너 후보는 뉴스가 없으므로 `newsFadeDetector.hasNewsFaded()`
  가 참이 되고, `retainDespiteNewsFade`(저평가+상대강세) 예외가 아니면 **전부 차단**된다.
  매수측 `NEWS_FADED` / `FADE_COOLDOWN` 게이트 제거 없이는 이 설계가 한 건도 매수하지 못한다.
- **DB 부하**: 스크리너 스냅샷은 60일 × 3,805종목 ≈ 228k 행 집계 1회. 여러 프로젝트가 공유하는
  20GB PDB 이므로 **단일 `GROUP BY` 쿼리**로 끝내고 발굴 주기(15분)에만 돈다.
- **가정**: `daily_ohlcv` KR 거래량은 정상(실측 최신/20일평균 중위 0.746배). US 는 깨짐(#885).

## 5. 아키텍처 개요

### 파일 구조

| 파일 | 구분 | 역할 |
|---|---|---|
| `autotrade/UniverseScreener.java` | **신규·순수** | 스냅샷 → 1차 선별 + 정렬 + 상위 N |
| `autotrade/ScreenCandidate.java` | **신규·record** | 스크리너 통과 1건(종목·시장·점수·근거) |
| `autotrade/ScreeningRow.java` | **신규·record** | 종목 1건의 로컬 집계(순수 입력) |
| `autotrade/ScreenFunnel.java` | **신규·record** | 단계별 잔존 수(AC8 관측용) |
| `autotrade/NewsRiskExclusion.java` | **신규·I/O** | 리스크 이벤트 기사 보유 종목 집합 |
| `market/DailyOhlcvMapper.java` | 수정 | `screeningSnapshot(since)` 추가 |
| `mapper/DailyOhlcvMapper.xml` | 수정 | 위 쿼리 |
| `autotrade/CandidateDiscoveryService.java` | 수정 | `refresh()` 재작성, 뉴스 발굴 제거 |
| `autotrade/AutoTradeScheduler.java` | 수정 | `NEWS_FADED`/`FADE_COOLDOWN` 제거, 촉매면제 상한 |
| `autotrade/ValuationClient.java` | 수정 | `getAnnualFinancials` 캐시 |
| `autotrade/AutoTradeProperties.java` | 수정 | 설정 4개 추가 |
| `resources/application.yml` | 수정 | 설정 기본값 |

### 데이터 흐름

```
                      ┌─────────────── I/O 경계 ───────────────┐
daily_ohlcv ─────────▶│ DailyOhlcvMapper.screeningSnapshot()   │
(60일·전 종목)         │   → List<ScreeningRow>                 │
                      │                                        │
stock_news ──────────▶│ NewsRiskExclusion.excludedSymbols()    │
(리스크 기사만)         │   → Set<String>                        │
                      │                                        │
야후(SPY) ───────────▶│ ValuationClient.getIndexReturnPct()    │  ※US 는 범위 외
                      └────────────────┬───────────────────────┘
                                       ▼
                      ┌──────── 순수 전략 로직 ─────────┐
                      │ UniverseScreener.screen(        │
                      │   rows, excluded, indexReturn,  │
                      │   params)                       │
                      │  1 데이터충분 → 2 유동성        │
                      │  → 3 상대강세 → 4 급등률상한     │
                      │  → 5 리스크배제 → 6 정렬·상위N  │
                      │   → (List<ScreenCandidate>,     │
                      │      ScreenFunnel)              │
                      └────────────────┬────────────────┘
                                       ▼
                      CandidateDiscoveryService.refresh()
                        · 구 후보(자동발견() 전부 비활성
                        · 스크리너 이탈분 비활성
                        · 신규분 insert (노트 "스크리너(")
                        · 수동 등록분 보존
                                       ▼
                             auto_trade_candidate (≤ N)
                                       ▼
                      AutoTradeScheduler.tick()  — 기존 게이트
                        ALREADY_HELD → MARKET_CLOSED → REGIME
                        → STOP_COOLDOWN → SAME_THEME
                        → POPULARITY → [API] 밸류에이션
                        → NO_PRICE → EXTENDED(3.0) → VALUATION
                        → [API] FUNDAMENTAL(§3 체크리스트) → BUY
                        ※ NEWS_FADED / FADE_COOLDOWN 제거
```

**I/O ↔ 순수 경계**: `UniverseScreener` 는 DB·API 를 모르는 순수 함수다. 모든 조회는
`CandidateDiscoveryService` 가 수행해 입력으로 넘긴다. 지수 수익률도 입력으로 받는다
(KR 은 스냅샷 안의 `069500` 에서 계산 가능하지만, US 확장 시 야후 경로가 필요하므로
처음부터 입력 파라미터로 둔다).

## 6. 데이터 모델

### `ScreeningRow` (순수 입력)

| 필드 | 타입 | 경계 검증 |
|---|---|---|
| `symbol` | String | null·빈값 → 행 폐기 |
| `market` | String | `KR`/`US`. 그 외 → 행 폐기 |
| `latestClose` | BigDecimal | `> 0` 아니면 행 폐기 |
| `closeBefore20` | BigDecimal | null 또는 `<= 0` → 상대강세 판정 불가 → 탈락 |
| `avgTurnover20` | BigDecimal | null → 0 으로 취급(유동성 탈락) |
| `low5` | BigDecimal | null 또는 `<= 0` → 급등률 판정 불가 → **탈락**(fail-closed) |
| `bars` | int | `>= 21` 아니면 탈락 |

> 급등률만 fail-**closed** 다 — 측정에서 급등 구간의 하드손절률이 최대 89.9% 였으므로
> "모르면 사지 않는다"가 맞다. (`PriceExtension` 은 fail-open 이지만 그건 이미 후보가 된
> 종목의 장중 판정이고, 여기선 후보 선별이므로 보수적으로 간다.)

### `ScreenCandidate` (순수 출력)

| 필드 | 타입 | 의미 |
|---|---|---|
| `symbol` / `market` | String | |
| `turnover` | BigDecimal | 20일 평균 거래대금(정렬 키) |
| `extensionPct` | double | 5일 저점 대비 상승률 |
| `excessReturnPct` | double | 20일 수익률 − 지수 20일 수익률 |
| `note` | String | `스크리너(2026-10-09, 거래대금 1,234억·초과수익 +3.2%p·급등 +1.1%)` |

`note` 는 `auto_trade_candidate.valuation_note`(VARCHAR2(500))에 그대로 저장한다.
접두사 `스크리너(` 가 **자동 등록 식별자**다(기존 `자동발견(` 와 구분).

### `ScreenFunnel` (관측)

`total`, `afterBars`, `afterLiquidity`, `afterExtension`, `afterRelativeStrength`,
`afterRiskExclusion`, `selected` — 7개 int. AC8 로그 1줄의 재료.

### 스키마 변경

**없음.** `auto_trade_candidate` 는 그대로 쓴다(`valuation_note` 재사용).

## 7. 함수 명세 (Function Specs)

| 함수 | 책임(1줄) | 시그니처(잠정) | 입력 | 출력 | 에러/실패 | 복잡? |
|------|-----------|----------------|------|------|-----------|-------|
| `UniverseScreener.screen` | 스냅샷을 1차 선별·정렬해 상위 N 후보를 낸다 | `static ScreenResult screen(List<ScreeningRow> rows, Set<String> excluded, Map<String,Double> indexReturnByMarket, ScreenParams p)` | §6 | `ScreenResult(List<ScreenCandidate>, ScreenFunnel)` | 입력 null/빈 → 빈 결과 + 0 퍼널 | **복잡** → `fn-screen.md` |
| `UniverseScreener.extensionPctOf` | 5일 저점 대비 상승률 | `static Double extensionPctOf(ScreeningRow r)` | row | % 또는 null | low5 ≤ 0 → null | 단순 |
| `UniverseScreener.excessReturnPctOf` | 지수 대비 20일 초과수익 | `static Double excessReturnPctOf(ScreeningRow r, Double indexRet)` | row, 지수수익 | %p 또는 null | 어느 한쪽 null → null | 단순 |
| `UniverseScreener.noteOf` | 후보 노트 문자열 생성 | `static String noteOf(LocalDate d, ScreenCandidate c)` | | String(≤500) | — | 단순 |
| `NewsRiskExclusion.excludedSymbols` | 리스크 기사 보유 종목 집합 | `Set<String> excludedSymbols(LocalDateTime since)` | 조회 하한 | 종목 집합 | 조회 실패 → 예외 전파(발굴 중단, fail-closed) | **복잡** → `fn-excludedSymbols.md` |
| `DailyOhlcvMapper.screeningSnapshot` | 종목별 로컬 집계 1회 조회 | `List<ScreeningRow> screeningSnapshot(@Param("since") LocalDate since)` | 시작일 | 행 목록 | SQL 예외 전파 | 단순(SQL) |
| `CandidateDiscoveryService.refresh` | 스크리너 결과로 후보 테이블 동기화 | `void refresh()` | — | — | 조회 실패 → 로그 후 이번 사이클 포기(기존 후보 유지) | **복잡** → `fn-refresh.md` |
| `CandidateDiscoveryService.isAutoRegistered` | 노트로 자동/수동 등록 구분 | `static boolean isAutoRegistered(String note)` | 노트 | boolean | null → false(수동 취급·보존) | 단순 |
| `ValuationClient.getAnnualFinancials` | 연간 실적 조회 + 캐시 | 기존 시그니처 유지 | | `AnnualFinancials` 또는 null | 실패는 FAILURE_CACHE_TTL 로 캐시 | 단순(기존 캐시 패턴 재사용) |
| `AutoTradeScheduler.catalystAllowed` 계산 | 촉매면제에 급등률 상한 적용 | 인라인 수정 | | boolean | — | 단순 |

## 8. 흐름 / 알고리즘

### 발굴 사이클 (15분)

1. `snapshot = dailyMapper.screeningSnapshot(today − 90일)`
2. `excluded = newsRiskExclusion.excludedSymbols(now − risk-exclusion-days)`
3. `indexReturn = { "KR": 069500 의 20일 수익률 }` — 스냅샷에서 계산. US 는 범위 외.
4. `result = UniverseScreener.screen(snapshot, excluded, indexReturn, params)`
5. 동기화(`fn-refresh.md`): 구 후보 비활성 → 이탈분 비활성 → 신규분 insert → 수동분 보존
6. `log.info` 로 `ScreenFunnel` 1줄 출력 (AC8)

### 진입 (1분 틱) — 변경점만

- `NEWS_FADED` / `FADE_COOLDOWN` 분기 **삭제**
- `EXTENDED` 임계 `max-extension-pct` = **3.0**
- `catalystAllowed` 에 급등률 상한 추가:
  ```
  catalystAllowed = rerateCatalyst
                    && ValuationChecker.withinCatalystBound(...)
                    && (extension == null || extension < catalyst-max-extension-pct)
  ```
  → 현재는 `extension` 이 `catalystAllowed` **뒤**에서 계산되므로 **계산 순서를 바꿔야 한다**
  (`current` 와 `extension` 을 촉매면제 판정보다 먼저 구한다). `NO_PRICE` 조기반환 위치가
  밸류에이션 API 호출보다 앞으로 올라가므로 **API 호출이 오히려 줄어드는** 부수효과가 있다.

### 설정 (`application.yml`)

| 키 | 기본값 | 근거 |
|---|---|---|
| `auto-trade.max-extension-pct` | `6.0` → **`3.0`** | 촉매일 손익분기(+3~6%가 −0.94%) |
| `auto-trade.catalyst-max-extension-pct` | **`15.0`** (신규) | +10~15% 하드손절 71%, +25%↑ 90% |
| `auto-trade.screen-top-n` | **`40`** (신규) | API 비용 상한. 실측 `POPULARITY` 통과 중위 8 |
| `auto-trade.screener-markets` | **`KR`** (신규) | US 는 #885/#886 미해결 |
| `auto-trade.risk-exclusion-days` | **`7`** (신규) | `RiskEventDetector` 24h 보다 길게 — 매수는 보수적으로 |

## 9. 엣지케이스 & 에러 처리

| 상황 | 처리 |
|---|---|
| 스냅샷이 빈 목록 | 빈 결과 + 퍼널 0. **기존 후보를 비활성화하지 않는다**(조회 장애를 이탈로 오인 금지) |
| `screeningSnapshot` SQL 예외 | 로그 후 이번 사이클 포기. 기존 후보 유지 |
| `excludedSymbols` 조회 예외 | 이번 사이클 포기(fail-closed). 리스크 배제 없이 등록하지 않는다 |
| 지수(`069500`) 데이터 없음 | 상대강세 판정 불가 → 해당 시장 전체 탈락(fail-closed) |
| `low5` 결측 | 해당 종목 탈락(fail-closed, §6) |
| 통과 종목이 N 미만 | 있는 만큼 등록 |
| 동률 거래대금 | `symbol` 오름차순 2차 정렬 → 결정론적 |
| 이미 보유 중인 종목이 통과 | 등록은 허용(`tick()` 이 `ALREADY_HELD` 로 걸러냄 — 기존 동작 유지) |
| 수동 등록 후보가 스크리너를 통과 못 함 | 보존(AC10) |
| 노트 500자 초과 | `noteOf` 에서 절단 |

## 10. 테스트 계획

### 단위 — `UniverseScreenerTest` (순수)

| 케이스 | 매핑 |
|---|---|
| 유동성 미달 종목이 탈락한다 | AC4 |
| 급등률 ≥ 임계 종목이 탈락한다 | AC6 |
| `low5` 결측은 fail-closed 로 탈락한다 | §6 |
| 지수보다 약한 종목이 탈락한다 | — |
| 지수 수익률 null 이면 해당 시장 전체가 탈락한다 | §9 |
| 배제 집합의 종목이 탈락한다 | AC2 |
| 통과분이 거래대금 내림차순 상위 N 으로 잘린다 | AC4 |
| 거래대금 동률이면 종목코드 오름차순 | §9 |
| `bars < 21` 탈락 | §6 |
| 퍼널 숫자가 단계별 잔존 수와 일치한다 | AC8 |
| 빈 입력 → 빈 결과·0 퍼널 | §9 |
| `screener-markets` 에 없는 시장은 제외된다 | §2 |

### 단위 — `NewsRiskExclusionTest` (모킹)

| 케이스 | 매핑 |
|---|---|
| `riskFlag` 가 NONE 아닌 기사의 종목이 집합에 든다 | AC2 |
| 제목 가드(`announcedRiskOf`) 만으로도 집합에 든다 | AC2 |
| 정상 기사만 있는 종목은 집합에 없다 | AC2 |
| `targets` 에 여러 종목이면 전부 집합에 든다 | AC2 |

### 단위 — `CandidateDiscoveryServiceTest` (모킹)

| 케이스 | 매핑 |
|---|---|
| 노트 `자동발견(` 활성 후보가 비활성화된다 | AC9 |
| 스크리너 이탈분이 비활성화된다 | — |
| 수동 등록분은 건드리지 않는다 | AC10 |
| 스냅샷 빈 목록이면 기존 후보를 비활성화하지 않는다 | §9 |
| 조회 예외 시 insert/deactivate 가 호출되지 않는다 | §9 |
| 등록 건수가 `screen-top-n` 이하다 | AC4 |
| 뉴스 기사만 있고 스크리너 미통과면 등록되지 않는다 | **AC1** |

### 단위 — `AutoTradeSchedulerTest` 추가

| 케이스 | 매핑 |
|---|---|
| 뉴스가 소멸한 후보도 매수 경로를 통과한다(`NEWS_FADED` 없음) | AC5 |
| 급등률 3.0 이상이면 `EXTENDED` 탈락 | AC6 |
| 급등률 15.0 이상이면 촉매가 있어도 `EXTENDED` 탈락 | AC6 |
| 급등률 10% + 촉매 있으면 통과(면제 상한 미만) | AC6 |

### 단위 — `ValuationClientTest`

| 케이스 | 매핑 |
|---|---|
| 동일 (종목,시장) 2회 호출 시 외부 요청 1회 | AC7 |
| 실패 결과도 캐시된다(짧은 TTL) | AC7 |

### 회귀 (기존 테스트 유지)

- `RiskEventDetectorTest` 전부 통과 — AC3
- `CatalystQualifierTest` / `TitleGuardTest` 는 **그대로 둔다**: `CatalystQualifier` 는 더 이상
  진입 경로에 없지만 `TitleGuard` 는 배제 필터가 계속 쓴다. `CatalystQualifier` 의 운명은 §13-2.

### 통합

`TossStockAppApplicationTests` 컨텍스트 로드 — `AutoTradeProperties` 레코드에 필드 4개가
추가되므로 **모든 생성 지점이 깨진다**(전례 있음). 패키징 전 `BUILD SUCCESS` 확인 필수.

## 11. 배포 / 롤백

- 설정만으로 되돌릴 수 없는 변경이다(코드 경로 제거). 롤백은 git revert + 재배포.
- `screener-markets` 를 빈 값으로 두면 **후보가 0건**이 되어 매수가 멈춘다(안전한 킬스위치).
- 배포 직후 확인: `GET /api/autotrade/status` 의 `scan` 에서 `NEWS_FADED` 가 사라졌는지,
  후보 수가 ≤40 인지, 퍼널 로그가 찍히는지.

## 12. 관측 (#884 연계)

`ScanVerdict` 스테이지 목록에서 `NEWS_FADED` / `FADE_COOLDOWN` 제거. 퍼널 로그 형식:

배포 후 실측(2026-10-09 08:59, 상한 0 킬스위치 상태):

```
스크리너(#887) KR: 전체 3464 → 바21+ 3456 → 유동성 1765 → 상대강세 1240
               → 급등<3.0% 845 → 리스크배제 844 → 선정 0 (상한 0)
```

설계 시 사전 측정은 679(급등률 단계)였고 구현 실측은 845 다. 원인은 급등률 분모의 창
길이다 — 사전 측정은 6바(최신+5), 구현은 `extension-lookback-days`(5바)를 쓴다.
6바 저점 ≤ 5바 저점이므로 6바 쪽 급등률이 더 크게 나와 더 많이 탈락한다. 구현이
매수 게이트({@code PriceExtension})와 같은 설정값을 쓰는 쪽이 맞으므로 5바로 간다.

**리스크배제 단계의 실효 범위가 좁다(845 → 844, 1건).** 이는 결함이 아니라 데이터 범위의
한계로 확인됐다 — 7일 기사 2,592건 중 리스크 제목 패턴에 걸리는 것이 22건이고, 그 기사들의
6자리 종목코드 타겟은 5종목뿐이다(`facts.riskFlag` 가 NONE 아닌 기사는 9건, `facts` 자체가
2026-10-08 13:38 부터만 적재). 원인은 뉴스 수집이 RSS 기반이라 **유상증자·감사의견 같은
전자공시(DART)를 직접 보지 않는다**는 점이다. → 후속 과제(§13-6).

## 13. 한계 / 미결 (원칙 §13)

1. **랭킹에 측정된 알파가 없다.** 정렬 키를 거래대금 내림차순으로 둔 것은 **방어적 선택**이다 —
   측정에서 대형주 집단이 유일하게 급등률 모멘텀이 양수였고(+3.42% → +6.80%), 슬리피지와
   생존편향이 작다. 그러나 "거래대금이 크면 성과가 좋다"를 직접 측정한 것은 **아니다**.
   부작용: 상위 40 이 거의 고정되어 대형주 위주로 굳을 수 있다(급등률·상대강세 필터가
   일부 회전을 만든다). → 운영 후 재검토 대상.
2. **`CatalystQualifier` 가 진입 경로에서 빠진다.** #865/#869/#877 로 만든 게이트는 통과율
   37% → 2.5% 로 줄이는 데 성공했지만, 그 효과는 `facts` 적재가 2026-10-08 13:38 부터여서
   **측정 가능 0건**이다(분석 문서 §8). 지우지 않고 남겨 두되, 진입에는 쓰지 않는다.
   향후 2~3개월 뒤 표본이 쌓이면 "스크리너 + 촉매 동시 충족" 조합을 측정할 수 있다.
3. **`POPULARITY` 게이트의 근거가 약하다.** 원칙 §3 에 "인기" 항목은 없고, `isPopular` 는
   OR 구조여서 거래량만 터져도 통과한다(TSM 10-08: 거래량 1.4배 + 가격 −1.79% → 통과).
   제거를 정당화하는 측정이 없어 유지하지만, 탈락 사유 분포를 보고 재검토한다.
4. **단일 레짐.** 근거 측정 전체가 KOSPI +183.6% 구간이다. 약세장에서는 뉴스 선별의 가치가
   더 클 수 있다. 이 설계는 "뉴스가 쓸모없다"가 아니라 **"측정된 구간에서 뉴스 단독 진입이
   음수였다"** 에 기반한다.
5. **ADR 후보.** "뉴스를 진입 근거로 쓰지 않는다"는 되돌리기 어려운 아키텍처 결정이다.
   구현 후 `docs/adr/` 에 기록할지 Reviewer 판단을 받는다.
6. **배제 필터의 실효 범위가 좁다(후속 과제).** §12 실측대로 3,464종목 중 리스크 기사가
   잡히는 종목이 5개 수준이다. 뉴스가 "살 이유"에서 "사지 말 이유"로 역할이 바뀐 만큼,
   그 역할을 제대로 하려면 RSS 뉴스가 아니라 **전자공시(DART) 직접 연동**이 필요하다
   — 유상증자·감사의견 거절·관리종목 지정은 모두 공시가 1차 출처다. 별도 이슈로 낸다.
7. **스크리너는 아직 성과가 측정되지 않았다.** 뉴스 진입이 음수였다는 것은 측정됐지만
   "유동성 상위 + 상대강세 + 급등률 하한"이 양수라는 것은 측정하지 않았다(§13-1).
   배포는 상한 0(킬스위치)으로 시작해 퍼널만 관찰한 뒤 사용자 승인으로 올린다.
