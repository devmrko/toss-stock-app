# 설계서: 시세 시각화 + 마켓 배너/상승비율 (#429)

> **상태**: Approved · **작성**: [AI] Architect · 2026-06-23
> **추적성** — Redmine: #429 · 기반: #419(일봉), #425(뉴스) · 참고: upbit-bot watchlist.html
> · 구현: `market/MarketMetrics.java`(순수), `market/MetricsService.java`, `web/MetricsController.java`, `web/MarketOverviewController.java`, `static/top50.html`·`watchlist.html`
> · 테스트: `MarketMetricsTest`

## 1. 목적
8080 코인 대시보드의 시각 요소를 주식 앱에 이식: 종목별 **흐름(스파크라인)·추세(MA)·딥(고점대비)·스윙(지지~저항 위치)·거래대금** + 상단 **마켓 배너**(전체장 호재/악재)와 **시장 상승비율(breadth)**. 모두 저장된 일봉(daily_ohlcv)으로 계산 → 추가 토스 호출 0.

## 2. 범위
- **포함**: `/api/metrics?symbols=`(종목별 지표 맵), `/api/market/overview`(배너+breadth). 탑50/워치리스트 렌더(스파크 SVG, 추세 ▲▼, 딥, 스윙바, 거래대금).
- **제외(Phase2)**: 초단기 5m/15m/1h 모멘텀(라이브 1분봉 필요), 즉석 백테, LLM 진입의견, 매수.

## 3. 인수조건
- [ ] `/api/metrics?symbols=a,b` → `{a:{flow[],trendPct,dipPct,swingPos,swingLow,swingHigh,tradingValue,lastClose}}`.
- [ ] `/api/market/overview` → `{marketLevel,marketNote,up,total,breadthPct}`.
- [ ] 탑50/워치리스트: 흐름 스파크라인, 추세(현재가 vs MA), 딥(고점대비 −%), 스윙(지지~저항 내 위치 %바), 거래대금(억/조).
- [ ] 상단: 마켓 배너(MARKET 뉴스 강도→호재/악재/중립 + note), 시장 상승비율 바(상승장/중립/하락장).
- [ ] 데이터 부족(일봉<2) 종목은 해당 셀 '-'.
- [ ] 추가 토스 API 호출 없음.

## 4. 지표 정의 (순수, MarketMetrics)
일봉 오름차순 리스트(close/high/low) + 최신가(없으면 마지막 close) 입력.
- **flow(N=20)**: 최근 N 종가 배열(스파크라인).
- **trendPct(win=30)**: ma=avg(최근 min(win,len) 종가); (last−ma)/ma×100. ▲(≥0)/▼.
- **dipPct(win=20)**: hi=max(최근 win 고가); (hi−last)/hi×100, ≥0. 호재(뉴스 S4/S5)면 임계 6%·아니면 15% 충족 시 '딥✓'(프론트 판정).
- **swing(win=20)**: lo=min(최근 win 저가), hi=max(최근 win 고가), pos=clamp((last−lo)/(hi−lo)×100,0,100). pos≤30 저점권(초록)·≥75 고점권(빨강). swings=국소반전 횟수(밴드 신뢰).
- **tradingValue**: 최신 volume × 최신 close (거래대금).

## 5. 아키텍처
```
[GET /api/metrics?symbols=]  MetricsService.compute(symbols)
   └ DailyOhlcvMapper.recentForSymbols(symbols, today-45d) → 그룹핑
   └ MarketMetrics.of(dailyList, lastPrice?) (순수) → Metrics
[GET /api/market/overview]
   └ StockNewsMapper.active("MARKET") 최강 → level/note
   └ DailyOhlcvMapper.breadth() → up/total (최신일 close>전일 close)
[top50.html/watchlist.html] load()에서 metrics·overview 병렬 fetch → 셀 렌더(폴링 유지)
```
- **경계**: 지표 계산 순수(MarketMetrics, 테스트). DB/HTTP는 mapper/service/controller.

## 6. 함수 명세
| 함수 | 책임 | 복잡? |
|------|------|-------|
| `MarketMetrics.of` | 일봉→지표(flow/trend/dip/swing/거래대금) | **복잡** |
| `MarketMetrics.swings` | 국소 반전 횟수 | 단순 |
| `MetricsService.compute` | 종목들 일봉 로드+지표 | **복잡** |
| `DailyOhlcvMapper.recentForSymbols` | 기간 일봉 조회 | 단순 |
| `DailyOhlcvMapper.breadth` | 최신일 상승/전체 | 단순 |
| `StockNewsMapper.active('MARKET')` | 시장 뉴스 | 단순(기존) |

> 복잡 함수 → `fn-metrics.md`.

## 7. 엣지케이스
- 일봉<2 → flow=[], trend/dip/swing=null. 프론트 '-'.
- hi==lo(횡보) → swingPos=50 기본.
- breadth 분모 0 → 0%.
- MARKET 뉴스 없음 → 중립 배너.

## 8. 테스트
- `MarketMetricsTest`: trend/dip/swing 계산값, flow 길이, 데이터부족 null, hi==lo 처리.

## 9. Open Questions
- Phase2 초단기(1분봉) 도입 시 캐시 전략(종목당 60s).
- breadth 기준 1d만 vs 1h(우린 일봉만이라 1d).
