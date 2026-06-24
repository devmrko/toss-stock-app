# 설계서: 페이지 반응 지연 개선 (N+1 제거 + 시세 SWR 캐시) (#436)

> **상태**: Approved · **작성**: [AI] Architect · 2026-06-25 · **유형**: 성능
> **추적성** — Redmine: #436 · 기반: #433(보유)·#414(워치리스트)·시세 · 구현: `holding/HoldingService`, `watchlist/WatchlistQuoteService`, `toss/PriceCache`, `toss/StockInfoCache`, `market/UniverseMapper(.xml)`, `market/DailyOhlcvMapper(.xml)` · 테스트: `WatchlistQuoteServiceTest`

## 1. 증상
`/api/holdings` ≈ 4.7s(10초 폴링), `/api/watchlist/quotes` ≈ 1.7s. 다른 화면(top50·overview·news)은 0.4~0.7s.

## 2. 원인
- **N+1 DB(보유)**: 종목당 `findSectorBySymbol` + `rangeSince` → 클라우드 ADB 왕복 ~20회(≈3s).
- **외부 토스 동기 호출**: 폴링마다 `getPrices`+`getStocks`(≈1.7s)가 응답을 막음.

## 3. 수정
### 3.1 N+1 제거
- 섹터: `UniverseMapper.sectorsForSymbols(symbols)` 1쿼리 → Map.
- 고점/MDD: `DailyOhlcvMapper.peakTroughBatch(pairs)` — `(symbol, fromDate)` 묶음을 `JOIN (… UNION ALL SELECT … FROM dual)` 으로 받아 종목별 `MAX(high)/MIN(low)` **집계 1쿼리**(원시 일봉 대량 전송 회피).
- 1·7일 등락: `recentForSymbols(symbols, today−20d)` 작은 범위 1쿼리.
- → 보유 DB 호출 ~22회 → 4회.

### 3.2 시세 캐시
- `PriceCache`(stale-while-revalidate): 신선(≤8s) 캐시 반환 / 낡음(>8s) 캐시 반환 + 백그라운드 갱신(단일 데몬 스레드) / 없음·>60s 동기 조회. 폴링이 외부콜에 안 막힘.
- `StockInfoCache`: 종목명은 불변 → 세션 캐시(미보유 심볼만 1회 조회). 폴링당 `/stocks` 콜 제거.

## 4. 결과(라이브)
| | 전 | 후 |
|---|---:|---:|
| /api/holdings | 4.7s | ~1.2s |
| /api/watchlist/quotes | 1.7s | ~0.6s |
- 데이터 동일(평단·고점·MDD·1·7일·뉴스). 테스트 33 통과.

## 5. 엣지/주의
- SWR 백그라운드 갱신 실패 → 무시(기존 캐시 유지, 다음 폴링 재시도).
- 가격은 최대 ~8s stale 가능(수동 대시보드 허용). 종목명은 세션 중 갱신 안 함(신규 심볼은 자동 조회).
- `peakTroughBatch` 빈 pairs 가드(보유 매수 없음 → 호출 안 함).

## 6. Open Questions
- (보류) 남은 DB 4쿼리 추가 통합 — 효과 대비 복잡도 낮아 보류.
- (보류) 시세 TTL/폴링주기 설정화(@Value).
