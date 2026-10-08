# 설계서: 시장상황 게이트 시장별 분리 (#879)

> **상태**: Approved · **작성**: [AI] Architect · 2026-10-08
> **추적성** — Redmine: #879 · 구현: `MarketRegimeGate`, `AutoTradeScheduler`,
>   `DailyOhlcvMapper`(+XML), `AutoTradeProperties`, `application.yml` ·
>   테스트: `MarketRegimeGateTest`, `AutoTradeSchedulerTest`

## 1. 목적 (Why)

`AutoTradeScheduler.tick()` 은 매수 스캔 전에 **전역으로 한 번** 게이트를 평가한다.

```java
if (!marketGateOpen()) return;   // 여기서 막히면 US 매수도 전부 스킵
scanCandidates(openSlots);
```

`marketGateOpen()` 이 쓰는 `dailyMapper.breadth(100)` 에는 **시장 필터가 없다**.
실질적으로 KR 전종목 상승비율이고, 임계값 `min-breadth-pct: 20` 도 **KR 분포로
캘리브레이션**된 값이다(`application.yml` 주석: 최근 25거래일 16.3~69.9%, 중앙값 ≈45%).

따라서 **코스피가 급락하면 그 이유로 미국 주식 매수가 막힌다.**

## 2. 실측 (2026-10-08 기준일)

| 시장 | 표본 | breadth |
|------|------|---------|
| KR | **3,699** | 30% |
| US | **7** | 43% (7개 중 3개 상승) |

**핵심 발견: 기준일 join 에 들어오는 US 종목이 7개뿐이다.** `daily_ohlcv` 전체에 US
종목이 60개 있지만, 기준일(KR 거래일)과 그 전 거래일 **양쪽에 봉이 있는** 종목이 7개다.
게다가 그 60개는 **뉴스에 언급돼 백필된 것**이라 모멘텀 종목 쪽으로 편향된 표본이다.

> **US breadth 는 계산할 수 없다.** 7종목으로 실매매를 게이트할 수 없고, KR 로
> 캘리브레이션한 20% 임계값을 US 에 적용할 근거도 없다.
>
> 반대로 현재 섞인 값에서 **US 기여는 0.19%**(7/3,706)라 KR 판정에는 실질 영향이 없다.
> 즉 이 이슈의 피해는 "KR 이 오염됐다"가 아니라 **"US 가 엉뚱한 신호로 막힌다"** 쪽이다.

## 3. 설계 (What)

### 3.1 게이트를 시장별로, 후보별 위치에서 평가한다

`tick()` 의 전역 1회 평가를 제거하고 `scanCandidates` 안에서 **그 후보의 시장** 기준으로
판정한다 — `MarketHours` 를 이미 후보별로 보고 있으므로 같은 자리에 둔다.

```
변경 전: tick() → marketGateOpen() 1회 → 닫히면 scanCandidates 자체를 스킵
변경 후: scanCandidates → 후보마다 regimeOpen(c.getMarket())
```

한 틱에서 같은 시장을 반복 조회하지 않도록 **틱 로컬 캐시**(`Map<String,Boolean>`)를 쓴다.

### 3.2 표본 부족은 '신호 없음'으로 보고 통과시킨다 (중립)

`"US 는 스킵"` 을 하드코딩하지 않고 **표본 수로 판단**한다. 그래야 나중에 US 커버리지가
늘면 **코드 변경 없이** 게이트가 작동한다(자가치유형).

| 조건 | 판정 |
|------|------|
| 표본 ≥ `min-breadth-sample` | breadth 로 정상 평가 |
| 표본 < `min-breadth-sample` | **신호 없음 → 통과**, 로그 1회 |
| 조회 실패·null | **신호 없음 → 통과**(기존 "데이터 없으면 중립값 50" 과 같은 방향) |

`min-breadth-sample` 기본값은 **100** 으로 둔다 — 기존 `breadth(minCoverage=100)` 가
이미 쓰던 수치와 같아 새 임계값을 발명하지 않는다.

### 3.3 US 에서 게이트를 빼도 되는가

현재 US 매수는 **KR breadth** 로 게이트되고 있는데 그건 US 안전신호가 아니다. 따라서 이
변경은 "보호장치 제거"가 아니라 **틀린 신호 제거**다.

US 매수에는 종목별 게이트가 그대로 남는다 — 저평가(PER/PBR), 펀더멘털 점수,
인기(거래대금·가격), **상대강세(SPY 대비)**, 급등 필터(5일 저점 대비), 슬롯·예산 상한.
특히 상대강세는 SPY 대비 우위를 요구하므로 **미국장이 무너지는 국면에서는 통과가
어려워진다** — 지수 기반 보호가 간접적으로는 남아 있다.

## 4. 함수 등재 (Function Registry)

| 함수 | 파일 | 책임 | I/O | 비고 |
|------|------|------|-----|------|
| `MarketRegimeGate.evaluate(int breadthPct, int sampleSize, Gate props)` | 기존 수정 | 표본 부족 시 통과 | **순수** | 시그니처 변경 |
| `DailyOhlcvMapper.breadth(market, minCoverage)` | 기존 수정 | 시장 필터 추가 | I/O | KR→`universe`, US→`us_universe` 조인 |
| `AutoTradeScheduler.regimeOpen(String market, Map cache)` | 신규(private) | 시장별 판정 + 틱 캐시 | I/O | |
| `AutoTradeScheduler.tick()` | 기존 수정 | 전역 게이트 호출 제거 | I/O | |
| `AutoTradeScheduler.scanCandidates` | 기존 수정 | 후보별 게이트 추가 | I/O | |

## 5. 인수조건 (Acceptance)

1. KR 후보는 KR breadth 로만 게이트된다(US 종목이 집계에 섞이지 않는다).
2. US 후보는 KR breadth 로 막히지 **않는다**.
3. 표본이 `min-breadth-sample` 미만이면 **통과**시키고 로그를 남긴다.
4. 표본이 충분해지면 해당 시장도 게이트가 작동한다(**코드 변경 없이**).
5. KR 판정 결과가 변경 전과 동일하다(US 기여 0.19% — 임계 통과/탈락이 뒤집히지 않음).
6. 한 틱에서 같은 시장의 breadth 를 **두 번 조회하지 않는다**.

## 6. 테스트 계획

`MarketRegimeGateTest`:
- 표본 충분 + breadth ≥ 임계 → true
- 표본 충분 + breadth < 임계 → false
- **표본 부족(7) + breadth 0 → true**(인수조건 3 — 신호 없음은 통과)
- 표본 0 → true
- `breadthPct` 범위 밖(−1, 101) → `IllegalArgumentException`(기존 계약 유지)

`AutoTradeSchedulerTest`:
- KR breadth 가 임계 미만일 때 **US 후보는 매수 시도된다**(인수조건 2 — 이 버그의 핵심)
- KR breadth 가 임계 미만일 때 KR 후보는 매수되지 않는다(인수조건 5)
- 같은 시장 후보가 여러 건이어도 `breadth` 조회는 1회(인수조건 6)

## 7. 리스크 / 되돌리기

| 리스크 | 완화 |
|--------|------|
| US 가 시장 레짐 보호 없이 매수됨 | §3.3 — 지금은 *틀린* 신호로 막고 있다. 종목별 게이트와 SPY 대비 상대강세가 남는다 |
| 표본 부족 판정이 KR 까지 통과시켜 버림 | KR 표본은 3,699 로 임계 100 을 크게 넘는다. 전종목 수집이 깨지면 그때는 breadth 자체가 무의미하므로 통과가 맞다 |
| 후보별 평가로 조회가 늘어남 | 틱 로컬 캐시로 시장당 1회(인수조건 6) |

되돌리기: `tick()` 에 전역 `regimeOpen("KR")` 호출을 되살리고 `scanCandidates` 의
후보별 호출을 제거.

## 8. 범위 밖

- **US 레짐 신호 확보** — 선택지는 (a) `daily_ohlcv` 의 US 커버리지 확대(현재 60종목),
  (b) SPY 기반 게이트(야후 경로가 이미 있으나 임계값 캘리브레이션 필요).
  둘 다 측정이 선행돼야 해서 별도 이슈로 둔다. §3.2 의 표본 조건 덕분에 (a) 가 되면
  코드 변경 없이 게이트가 켜진다.
