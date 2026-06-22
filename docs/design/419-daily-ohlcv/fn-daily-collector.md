# 함수 설계: 일봉 수집기 (#419)

> 부모: README.md · 대상: DailyCollector.fetchYear/backfillYear/refreshLatest

## fetchYear(symbol) — 1년 일봉(페이지네이션)
```
acc = []; before = null; cutoff = today - 1년
loop (최대 3페이지):
  candles = client.getDailyCandles(symbol, 200, before)   # before=null 이면 최신부터
  if candles empty: break
  acc += candles
  oldest = candles.last.timestamp
  nextBefore = response.nextBefore
  if oldest <= cutoff or nextBefore == null: break
  before = nextBefore                                      # ISO+TZ 그대로
return acc filter(ts >= cutoff)
```
- 토스 제약: count≤200, before=<직전 nextBefore>. 보통 2페이지면 250일 확보.

## backfillYear() — 전종목 백필(@Async)
```
status.start(total=universe)
for u in universe:
   try: for c in fetchYear(u.symbol): mapper.upsert(toDaily(u.symbol,c))
   catch 429: 백오프 재시도; catch other: 스킵
   status.inc(); sleep(throttle)
status.done
```

## refreshLatest() — 당일 갱신(@Async, @Scheduled)
```
for u in universe:
   candles = client.getDailyCandles(u.symbol, 2)     # 당일+전일
   for c in candles: mapper.upsert(toDaily(u.symbol,c))
   sleep(throttle)
```

## toDaily(symbol, candle) — 순수
- candle(문자열) → DailyOhlcv(symbol, trade_date=ts.toLocalDate, open/high/low/close BigDecimal, volume Long). 파싱 실패시 해당 필드 null.

## upsert — MERGE
```
MERGE INTO daily_ohlcv t USING (select :symbol,:d ... from dual) s
ON (t.symbol=s.symbol AND t.trade_date=s.trade_date)
WHEN MATCHED THEN UPDATE SET o/h/l/c/v
WHEN NOT MATCHED THEN INSERT (...)
```

## 테스트
- toDaily 변환(타임스탬프→date, 숫자 파싱).
- fetchYear cutoff 종료 조건(모킹: 2페이지 후 중단).
