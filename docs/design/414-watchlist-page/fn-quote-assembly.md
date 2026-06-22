# 함수 설계: quote 집계/정렬 (#414)

> 부모: `README.md` · 대상: `WatchlistQuoteService.assemble` / `toRow`

## assemble() — 워치리스트 → 거래량 정렬된 quote 목록
### 책임
워치리스트 종목을 토스 시세/이름/일봉으로 보강해 **거래량 내림차순** 행을 만든다. 부분 실패를 허용한다.

### 알고리즘
```
rows = mapper.findAll()
if rows empty: return []
symbols = distinct(rows.symbol)
priceBySym = index(getPrices(symbols), by symbol)        # 1 call
nameBySym  = index(getStocks(symbols), by symbol)        # 1 call
quotes = []
for r in rows:
  try:
    candles = candleCache.get(r.symbol)                  # TTL 60s; miss→getDailyCandles(symbol,2)
    quotes += toRow(r, priceBySym[r.symbol], nameBySym[r.symbol], candles)
  catch any e:
    quotes += staleRow(r, nameBySym[r.symbol])           # stale=true, 값 가능분만
sort quotes by volume desc (nulls last)
assign rank = 1..n
return quotes[0:50]
```

### 엣지/실패
- 종목 1개 실패가 전체를 막지 않음(try per symbol).
- 가격/이름 맵 miss → 해당 필드 null/"-".
- 정렬 시 volume null 은 맨 뒤.

## toRow(watchlist, price, name, candles) — 한 행 + 등락률
### 입력/출력
- in: Watchlist(id,symbol,memo), price(lastPrice or null), name(or null), candles(List, 최신순 0=오늘,1=전일).
- out: WatchlistQuote.

### 계산
```
last  = price?.lastPrice
vol   = candles[0]?.volume
prev  = candles.size>=2 ? candles[1].closePrice : null
chgAmt = (last!=null && prev!=null) ? last - prev : null
chgRate= (chgAmt!=null && prev!=0) ? round(chgAmt/prev*100, 2) : null
return WatchlistQuote(id, symbol, name?:"-", last, prev, chgAmt, chgRate, vol, currency, rank=0, stale=false)
```
- 모든 수치는 `BigDecimal`(가격) / `Long`(거래량). 0 division 가드.
- rank 는 assemble 정렬 후 주입(여기선 0).

### 테스트(Mockito, 외부 호출 없음)
- 거래량 desc 정렬: vol [100,300,200] → 순서 300,200,100, rank 1,2,3.
- 등락률: last 352500, prev 350500 → +2000, +0.57%.
- prevClose 누락(candles 1개) → chgRate null.
- 50행 컷: 60개 입력 → 50개, rank 1..50.
- 부분실패: 한 종목 candles 예외 → 그 행 stale=true, 나머지 정상.
