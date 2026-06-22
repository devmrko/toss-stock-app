# 설계서: 워치리스트 거래량 모니터링 페이지 (#414)

> **상태**: Approved
> **작성**: [AI] Architect · **최종수정**: 2026-06-22
> **추적성** — Redmine: #414 · 관련 ADR: ADR-0001(RestClient), ADR-0003(워치리스트 유니버스)
> · 구현: `src/main/resources/static/watchlist.html`, `web/WatchlistController.java`, `watchlist/WatchlistQuoteService.java`
> · 테스트: `WatchlistQuoteServiceTest`, `WatchlistApiIT`

## 1. 목적 (Why)
`http://localhost:8080/watchlist.html` 에서 **내가 담은 종목**의 시세·등락률·거래량을 한 화면에서 모니터링한다.
목표(1줄): "워치리스트 종목을 거래량 순으로 정렬해 실시간(폴링) 모니터링한다."

## 2. 범위 (Scope)
- **포함**:
  - 정적 페이지 `watchlist.html` (Spring 정적 서빙, 빌드툴 없음 — 순수 HTML/JS/CSS).
  - 종목 추가/삭제(워치리스트 CRUD) + 5초 폴링 자동 갱신.
  - 행 = 종목코드·이름·현재가·등락률·거래량(당일), **거래량 내림차순 정렬 + 순위 표시**, 최대 50행.
  - 백엔드 집계 API `GET /api/watchlist/quotes`.
- **제외 (out of scope)**:
  - **시장 전체 거래량 탑50(B안)** — 토스에 랭킹 API 없음 → 별도 이슈(전 종목 유니버스+일배치).
  - 주문/체결, 차트, 실시간 웹소켓(이번엔 폴링), 인증/멀티유저.

## 3. 인수조건 (Acceptance Criteria)
- [ ] `GET /watchlist.html` 200, 표가 렌더된다.
- [ ] 워치리스트가 비어 있으면 "종목을 추가하세요" 안내, 에러 없음.
- [ ] 종목 추가(POST) 후 표에 나타나고, 삭제(DELETE) 후 사라진다.
- [ ] 각 행에 현재가·등락률(전일종가 대비)·당일거래량 표시.
- [ ] **거래량 내림차순 정렬**, 1..N 순위 컬럼, 50행 초과 시 상위 50만.
- [ ] 5초마다 시세가 자동 갱신된다(폴링).
- [ ] 토스 API 일시 실패 시 페이지가 죽지 않고 직전 값 유지 + 표시.

## 4. 컨텍스트 & 제약
- **의존**: 토스 `prices`(다건 1콜), `stocks`(이름, 다건 1콜), `candles?symbol=&interval=1d&count=2`(거래량+전일종가, **종목당 1콜**). Oracle `watchlist`.
- **레이트리밋(BASIC)**: 시세 폴링은 `prices` 1콜/주기. 거래량/전일종가는 변동이 적어 **캐시(TTL 60s)** 로 콜 절감(N종목×최대 1콜/분).
- **가정**: 워치리스트 종목 수 ≤ 50 (초과분은 거래량 상위 50만 표시).
- **장중/장외**: 장 마감 후엔 값이 고정 — 정상.

## 5. 아키텍처 개요
```
브라우저 watchlist.html
  │  (fetch, 5s 폴링)
  ├── GET  /api/watchlist/quotes ─┐
  ├── POST /api/watchlist          │
  └── DELETE /api/watchlist/{id}   │
                                   ▼
                        [WatchlistController]
                                   │
                                   ▼
                        [WatchlistQuoteService]  ← 순수 집계/정렬 로직
                          │            │
                          ▼            ▼
                  [WatchlistMapper]  [TossApiClient] (prices/stocks/candles)
                     (Oracle)          │  candles는 [CandleCache] TTL 60s
```
- **I/O ↔ 순수 로직 경계**: 정렬·등락률 계산은 `WatchlistQuoteService` 의 순수 메서드(`assemble`, `toRow`)로 분리해 단위 테스트. 외부 호출은 mapper/client/cache.

## 6. 데이터 모델
- **WatchlistQuote**(응답 DTO): `id:Long`, `symbol`, `name`, `lastPrice:BigDecimal`, `prevClose:BigDecimal`,
  `changeAmount:BigDecimal`, `changeRate:Double(%)`, `volume:Long`, `currency`, `rank:int`, `stale:boolean`.
- **AddRequest**(요청): `symbol`(필수, 6자리 등 비공백), `memo`(선택).
- **TossCandle**(매핑): `timestamp`, `openPrice`, `highPrice`, `lowPrice`, `closePrice`, `volume`, `currency`.
  - 응답 래퍼: `{"result":{"candles":[...],"nextBefore":...}}`.
- **경계 검증**: symbol 공백/길이, 중복 추가 거부(이미 있으면 409 또는 무시).

## 7. 함수 명세 (Function Specs)

| 함수 | 책임(1줄) | 시그니처(잠정) | 입력 | 출력 | 에러 | 복잡? |
|------|-----------|----------------|------|------|------|-------|
| `WatchlistQuoteService.assemble` | 워치리스트→정렬된 quote 목록 | `List<WatchlistQuote> assemble()` | - | quotes | 부분실패 허용 | **복잡** |
| `WatchlistQuoteService.toRow` | 한 종목 행 조립+등락률 계산 | `WatchlistQuote toRow(Watchlist, price, name, candles)` | 원천 | row | null 가드 | **복잡** |
| `TossApiClient.getDailyCandles` | 일봉 N개 조회 | `List<TossCandle> getDailyCandles(String symbol,int count)` | symbol,count | candles | 4xx/5xx | 단순 |
| `CandleCache.get` | TTL 캐시 조회/적재 | `List<TossCandle> get(String symbol)` | symbol | candles | - | 단순 |
| `WatchlistMapper.insert` | 종목 추가 | `int insert(Watchlist)` | row | 1 | 중복 | 단순 |
| `WatchlistMapper.deleteById` | 종목 삭제 | `int deleteById(Long id)` | id | 0/1 | - | 단순 |
| `WatchlistMapper.findBySymbol` | 중복 체크 | `Watchlist findBySymbol(String)` | symbol | row/null | - | 단순 |
| `WatchlistController.quotes` | 집계 API | `List<WatchlistQuote> quotes()` | - | quotes | 502 | 단순 |
| `WatchlistController.add/delete` | CRUD | `... add(AddRequest)/delete(id)` | req | 201/204 | 400/409 | 단순 |

> 복잡 함수 `assemble`/`toRow` → `fn-quote-assembly.md`.

## 8. 흐름 / 알고리즘
`assemble()`:
1. `rows = mapper.findAll()`; 비면 `[]` 반환.
2. `symbols = distinct(rows.symbol)`.
3. `prices = tossApiClient.getPrices(symbols)` → map(symbol→lastPrice). (1콜)
4. `names = tossApiClient.getStocks(symbols)` → map(symbol→name). (1콜)
5. 각 symbol: `candles = candleCache.get(symbol)` (TTL 60s, miss 시 `getDailyCandles(symbol,2)`).
6. `toRow`: lastPrice, prevClose=candles[1].close, volume=candles[0].volume,
   changeAmount=last-prevClose, changeRate=round(changeAmount/prevClose*100, 2).
7. **거래량 desc 정렬**, rank 부여(1..), 상위 50 자른 뒤 반환.
8. 개별 종목 조회 실패 → 그 행은 `stale=true`로 직전/부분 데이터 유지(전체 실패 아님).

## 9. 엣지케이스 & 에러 처리
- 워치리스트 비어있음 → 빈 배열 + 프런트 안내.
- prevClose=0/누락 → changeRate=null, 표시 "-".
- candles 부족(신규상장 등 1개) → prevClose 없음 처리.
- 토스 5xx/타임아웃 → `assemble` 은 가능한 행만 반환, 실패 행 `stale=true`. 프런트는 마지막 성공값 유지.
- 중복 symbol 추가 → 409(또는 무시). 잘못된 symbol → stocks 결과 없음 시 그래도 추가하되 이름 "-".
- **안전기본값**: 주문 경로 없음(읽기 전용) → 자금 리스크 0.

## 10. 테스트 계획
- **단위(`WatchlistQuoteServiceTest`, Mockito)**: 정렬(거래량 desc)·등락률 계산·prevClose 누락·50행 컷·부분실패 stale.
- **통합(`WatchlistApiIT`, `@ActiveProfiles("oracle")`, env 게이트)**: add→quotes(거래량 정렬 확인)→delete 라운드트립(실 Oracle + 실 토스).
- **수동(verify 스킬)**: 브라우저로 `/watchlist.html` 열어 추가/삭제/정렬/폴링 확인.

## 11. 리스크 & 대안 검토
- 거래량/전일종가 콜 비용: 종목당 candles 1콜 → **캐시(60s)** 로 완화. 대안(폐기): prices 에 거래량 없음 / trades 합산은 부정확·고비용.
- 정렬 위치: 서버 정렬(채택, 50컷 일관) vs 클라이언트 정렬(데이터 과다 전송). → 서버.
- 폴링 vs 웹소켓: BASIC tier·단순성 → 폴링 5s(채택). 실시간성 필요 시 추후.

## 12. 미해결 질문 (Open Questions)
- 폴링 주기 5s 가 레이트리밋에 안전한지 운영 측정 필요(필요 시 10s).
- 장중 거래량 캐시 60s 적절성(체결 활발 종목은 더 짧게?).
- 시장 전체 탑50(B안) 착수 시점 — 별도 이슈.
