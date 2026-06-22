# 함수 설계: 거래량 스캔 배치 (#416)

> 부모: `README.md` · 대상: `Top50Service.refresh/scanVolumes/enrich`

## scanVolumes(universe) — 스로틀+백오프 전종목 거래량
```
results = []
for u in universe:
  attempt = 0
  while true:
    try:
      vol = client.getDailyCandles(u.symbol, 1)[0].volume   # 없으면 null
      results.add(SymVol(u, vol)); break
    except TossApiException e where e.status==429:
      attempt++; if attempt>3: results.add(SymVol(u,null)); break
      sleep(1500 * attempt)                                  # 선형 백오프
    except any:
      results.add(SymVol(u,null)); break                     # 상폐/정지 등 스킵
    status.scanned++
  sleep(110ms)                                               # ≈9콜/s, 한도 이하
return results
```
- 한도(실측 ~25버스트/10초) 안전 여유. throttle/backoff 상수는 설정화.

## rankTop50(symVols) — 순수
- volume null 제외 → volume desc 정렬 → 상위 50 → rnk 1..50 부여.

## enrich(top50) — 현재가/등락률 보강
```
symbols = top50.symbol
priceBySym = index(client.getPrices(symbols))                 # 1~2콜(다건)
for sv in top50:
  candles = client.getDailyCandles(sv.symbol, 2)              # 50콜
  last = priceBySym[sv].lastPrice
  prev = candles.size>=2 ? candles[1].close : null
  changeRate = (last,prev 유효) ? round((last-prev)/prev*100,2) : null
  -> RankRow(rnk, symbol, name, market, last, prev, changeRate, volume=sv.vol)
```

## refresh() — 오케스트레이션(@Async)
```
if status.state==RUNNING: return
status = RUNNING, total=universe.size, scanned=0, startedAt=now
try:
  uni = universeMapper.findAll()
  vols = scanVolumes(uni)
  top = rankTop50(vols)
  rows = enrich(top)
  volumeRankMapper.replaceAll(rows, asOf=now)     # 트랜잭션: deleteAll+insert
  status = DONE, asOf=now
catch e:
  status = ERROR, message=e
```
- 교체 저장은 성공 시에만(부분 실패로 기존 결과 날리지 않음).

## 테스트(단위, 외부 호출 없음)
- rankTop50: vol [10,null,30,20]+60개 → null 제외, desc, 50컷, rnk 연속.
- 등락률 계산(enrich의 순수부 toRankRow): last352500/prev350500 → +0.57%.
