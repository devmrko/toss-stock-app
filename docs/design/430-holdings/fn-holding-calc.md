# 함수 설계: 보유 지표 계산 (#430)

> 부모: README.md · 대상: HoldingCalc.of / HoldingService.list

## HoldingCalc.of(h, name, sector, current, peak, trough, now) → HoldingView (순수)
```
stopPrice   = buy × (1 − stopPct/100)              # 항상(매수가 기반)
returnPct   = cur? (cur−buy)/buy×100 : null
returnAmount= (cur,qty)? (cur−buy)×qty : null
stopDistPct = cur? (cur−stopPrice)/cur×100 : null  # +여유 / −이탈
belowStop   = cur!=null && cur ≤ stopPrice
daysHeld    = max(0, days(now − buyAt))
ddFromPeak  = (peak,cur)? (peak−cur)/peak×100 : null
stopHit     = trough!=null && trough ≤ stopPrice
```
- 0 분모 → null. 모든 % 2자리 반올림.

## HoldingService.list() → List<HoldingView>
```
rows = mapper.findAll(); symbols = distinct
priceBySym = getPrices(symbols)   # 현재가 1콜
nameBySym  = getStocks(symbols)   # 이름 1콜
for h in rows:
   sector = universe.findSectorBySymbol(h.symbol)
   {peak,trough} = dailyMapper.rangeSince(h.symbol, h.buyAt.date)
   HoldingCalc.of(h, name, sector, priceBySym[h.symbol], peak, trough, now)
```
- 외부 실패는 안전 폴백(빈 맵/ null). 스탑가는 외부 무관 항상 계산.

## 테스트(순수)
- 이익/손실 returnPct, stopPrice, stopDistPct 부호, belowStop, ddFromPeak, daysHeld, 현재가 null 처리.
