# 설계서: 탑50·워치리스트 뉴스 S뱃지 통합 (#426)

> **상태**: Approved · **작성**: [AI] Architect · 2026-06-23
> **추적성** — Redmine: #426 · 기반: #425(뉴스), #416(탑50), #414(워치리스트)
> · 구현: `news/NewsSignals.java`, `web/NewsController#signals`, `static/top50.html`·`watchlist.html`, `WatchlistQuote.sector`
> · 테스트: `NewsSignalsTest`

## 1. 목적
탑50/워치리스트 각 행에 그 **종목 또는 섹터의 활성 뉴스 S레벨**을 뱃지(🔴/🟢)로 보여, 시세와 뉴스를 한 화면에서 본다.

## 2. 범위
- **포함**: 활성 뉴스 sentiment 집계 API(`/api/news/signals`), 두 페이지에서 행별 뱃지 렌더, 워치리스트 quote에 `sector` 추가.
- **제외**: 행 클릭 시 뉴스 상세(후속), 시장(MARKET) 뱃지(페이지 상단 별도 표기는 후속).

## 3. 인수조건
- [ ] `GET /api/news/signals` → `{"005930":"S2","반도체":"S4",...}` (활성·미만료 중 key별 **최강 강도** 레벨).
- [ ] 탑50 행: `max(signal[symbol], signal[sector])` 뱃지. 없으면 미표시.
- [ ] 워치리스트 행: 동일(quote.sector 사용).
- [ ] S3/없음은 뱃지 미표시.

## 4. 데이터 & 알고리즘
- `StockNewsMapper.activeSentiments()` → 활성 행들의 sentiment CSV 목록.
- `NewsSignals.aggregate(List<String>)`: 각 `key:level` 파싱 → key별 **strength 최대** 레벨 유지(동일강도는 먼저=최근 우선, active 정렬이 최신순). → `Map<String,String>`.
- 페이지: 행의 `symbol`,`sector` 로 map 조회 → 강한 쪽 1개 뱃지.
- strength: S1/S5=2, S2/S4=1, S3=0.

## 5. 함수 명세
| 함수 | 책임 | 복잡? |
|------|------|-------|
| `NewsSignals.aggregate` | sentiment 목록→key별 최강레벨 맵 | **복잡** |
| `StockNewsMapper.activeSentiments` | 활성 sentiment 조회 | 단순 |
| `WatchlistQuoteService.assemble` | quote에 sector 채움 | 단순(기존+조회) |

## 6. 엣지케이스
- 같은 key 호재·악재 공존 → 강도 큰 쪽; 동일강도면 최근(목록 앞). 방향 상충은 강도 우선.
- 워치리스트 종목이 universe 밖 → sector null, 종목 뱃지만.
- 뉴스 0건 → 빈 맵, 뱃지 없음(시세 화면 정상).

## 7. 테스트
- `NewsSignalsTest`: 다건 집계 최강 유지, 빈 입력, 잘못된 토큰 무시.

## 8. Open Questions
- MARKET 뱃지 페이지 상단 배너로 노출할지(후속).
