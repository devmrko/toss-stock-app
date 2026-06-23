# 함수 설계: `PositionCalc.of` (#433)

> 종목 1개의 거래목록(BUY/SELL) → 평균단가 기준 집계 `PositionView`. 순수 함수.

## 시그니처
```java
static PositionView of(String symbol, String name, String sector,
                       List<Holding> trades,        // 그 종목의 전 거래(시간 무관)
                       BigDecimal current,           // 현재가(없으면 null)
                       BigDecimal peak, BigDecimal trough,  // 최초매수일 이후 고/저
                       LocalDateTime now)
```

## 알고리즘
1. `trades` 를 `tradedAt(buyAt)` 오름차순 정렬. 빈 목록이면 호출 안 됨(서비스가 보장).
2. `firstBuyAt` = 최초 BUY 의 tradedAt. `stopPct` = 가장 최근 BUY 의 stopPct(없으면 0).
3. 수량 완전성: 모든 BUY 의 quantity != null 이면 `weighted`, 아니면 `fallback`.
4. **weighted**: 5절 의사코드대로 boughtQty/boughtCost/soldQty/realized 누적.
   - SELL 시 `avg=boughtCost/boughtQty` 로 실현손익 적립 후 boughtQty/boughtCost 를 `q×avg` 만큼 차감(평단 불변).
   - netQty=boughtQty, avgCost=netQty>0?boughtCost/netQty:마지막 avg.
5. **fallback**: avgCost=Σ(BUY price)/(BUY 수), netQty=null, unrealizedAmount/realizedAmount=null.
6. 지표: stopPrice/belowStop/stopDistPct/ddFromPeak/stopHit/daysHeld (430 동일식, buy→avgCost).
7. `trades[]` = 각 거래를 TradeView 로(returnPct = current?(current−price)/price×100:null).

## 반올림
- 금액(amount): `long`(원). pct: 소수 2자리 HALF_UP. avgCost/stopPrice: 2자리.

## 엣지
| 상황 | 처리 |
|------|------|
| BUY 만 1건 | netQty=q, avgCost=price |
| 분할매수 | 가중평균 |
| 일부매도 | 평단 불변, realized>0/<0, netQty 감소 |
| 전량매도 netQty=0 | unrealized null, realized 만 |
| 과매도 netQty<0 | 음수 그대로(표시만) |
| BUY 수량 null 포함 | fallback |
| current null | unrealized/stopDist '-', stopPrice 표시 |
| peak/trough null | ddFromPeak/stopHit '-' |

## 테스트 → `PositionCalcTest`
- weighted 가중평단, 일부매도 후 평단·실현·순수량, 전량매도, fallback, 스탑/MDD 부호.
