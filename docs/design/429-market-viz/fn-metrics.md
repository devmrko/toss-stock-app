# 함수 설계: 시세 지표 (#429)

> 부모: README.md · 대상: MarketMetrics.of / MetricsService.compute

## MarketMetrics.of(daily 오름차순, lastPrice?) → Metrics
```
if daily.size<2: return empty
closes/highs/lows = daily 의 종/고/저 (long)
last = lastPrice ?: closes.last
flow      = closes 최근 20
ma        = avg(closes 최근 30);  trendPct = (last-ma)/ma*100
hi        = max(highs 최근 20);   dipPct   = max(0,(hi-last)/hi*100)
lo        = min(lows  최근 20)
swingPos  = hi==lo ? 50 : clamp((last-lo)/(hi-lo)*100,0,100)
swings    = 국소 반전수(closes 최근 20)
tradingValue = lastBar.volume * lastBar.close
```
- 모든 % 소수 2자리 반올림. 데이터 부족/0분모 → 해당 값 null.

## MetricsService.compute(symbols) → Map<symbol,Metrics>
```
capped = distinct(symbols) 최대 80
rows = dailyMapper.recentForSymbols(capped, today-45d)   # 한 쿼리
group by symbol (오름차순) → MarketMetrics.of(list,null)
```
- 추가 토스 호출 없음(저장 일봉만). 폴링마다 1 DB 쿼리.

## 테스트
- of(): flow/trend/dip/swing/거래대금 수치, 데이터부족 empty, hi==lo→50, lastPrice 우선.
