# 설계서: 보유 거래원장(매수/매도·평균단가 집계) (#433)

> **상태**: Approved · **작성**: [AI] Architect · 2026-06-24
> **추적성** — Redmine: #433 · 기반: #430(보유)·#419(일봉)·시세(prices) · 구현: `holding/*`, `web/HoldingController.java`, `static/holdings.html`, `db/holding.sql`
> · 테스트: `PositionCalcTest`

## 1. 목적
#430 의 "1행=1매수" 보유를 **거래 원장**으로 확장한다. 한 종목에 **매수/매도 다수 거래**를 등록하고, **평균단가** 기준으로 종목 단위 **순보유·평가손익(미실현)·실현손익**을 보여준다. 보유 화면에서 종목별로 **일봉 백필**을 수동 실행한다.

## 2. 범위
- **포함**: 거래(BUY/SELL) 추가/삭제, 종목 단위 평균단가 집계, 거래내역 펼침, 종목별 백필 버튼.
- **제외**: FIFO/세무 원가, 수수료·세금, 실제 주문 연동, 공매도(순수량 음수는 허용하되 표시만).

## 3. 인수조건
- [ ] 추가 폼에 **매수/매도 토글**. 공통: `symbol, tradedAt, price, quantity, stopPct, side`. 매도는 `quantity` 필수.
- [ ] 같은 종목 다수 거래 등록 가능. 응답은 **종목 1행으로 집계**.
- [ ] 종목 집계: `순수량=Σ매수Q−Σ매도Q`, `평단=매수 가중평균`, `미실현손익=순수량×(현재−평단)`, `실현손익=Σ매도Q×(매도가−평단)`.
- [ ] 집계행 펼치면 그 종목의 **거래내역**(side·일시·가격·수량) + 각 거래 삭제(✕).
- [ ] 종목별 **↻백필** 버튼 → `POST /api/daily/backfill-symbol?symbol=&from=<최초매수일>`.
- [ ] BUY 등록 시에만 자동 백필. SELL 은 백필 안 함.
- [ ] 스탑가=평단×(1−stopPct/100), 현재가≤스탑가 → 🔴. 매수후고점/MDD 는 **최초 매수일 이후** 기준.
- [ ] 수량 미입력(레거시) 종목은 평단=단순평균, 수량기반 손익 '-' 로 폴백.
- [ ] 합계: 총 미실현손익·총 실현손익·평균 미실현%.

## 4. 데이터 모델
- **HOLDING**(거래원장; #430 테이블 재사용 + 컬럼 추가): `id PK`, `symbol`, `buy_at`(=거래일시), `buy_price`(=체결가), `quantity`, `side VARCHAR2(4) DEFAULT 'BUY' NOT NULL`(신규), `stop_pct`, `memo`, `created_at`. 기존 행은 마이그레이션으로 `BUY`.
- **TradeView**(거래 1건): `id, side, tradedAt, price, quantity, returnPct`.
- **PositionView**(종목 집계, 응답): `symbol, name, sector, netQty, avgCost, currentPrice, unrealizedPct, unrealizedAmount, realizedAmount, daysHeld, stopPct, stopPrice, stopDistPct, belowStop, peakSinceBuy, ddFromPeak, stopHitSinceBuy, firstBuyAt, trades[]`.

## 5. 계산 (순수, PositionCalc) — 평균단가
```
trades 를 tradedAt 오름차순.
boughtQty, boughtCost, soldQty, realized = 0
for t in trades:
  if BUY:  boughtQty+=q; boughtCost+=q×price
  if SELL: avg=boughtCost/boughtQty; realized+=q×(price−avg)
           boughtCost-=q×avg; boughtQty-=q; soldQty+=q     # 평균단가법: 매도해도 평단 불변
netQty = boughtQty
avgCost = netQty>0 ? boughtCost/netQty : 마지막 avg
unrealizedAmount = (netQty>0 && cur)? netQty×(cur−avgCost) : null
unrealizedPct    = (netQty>0 && cur && avgCost>0)? (cur−avgCost)/avgCost×100 : null
realizedAmount   = soldQty>0 ? round(realized) : null
stopPrice  = avgCost×(1−stopPct/100)         # stopPct = 가장 최근 BUY 의 값
belowStop  = cur!=null && cur≤stopPrice
stopDist%  = cur? (cur−stopPrice)/cur×100 : null
peak/trough= rangeSince(symbol, firstBuyDate)
ddFromPeak = (peak,cur)? (peak−cur)/peak×100 : null
stopHit    = trough!=null && trough≤stopPrice
daysHeld   = floor(now − firstBuyAt, days)
```
- **폴백**: 어떤 BUY 든 `quantity==null` 이면 `avgCost=BUY가 단순평균`, `netQty=null`, 손익 amount=null(미실현%만).

## 6. 아키텍처
```
[POST /api/holdings {side,...}] HoldingMapper.insert  (BUY면 collector.backfillSymbol)
[DELETE /api/holdings/{id}]     HoldingMapper.deleteById  (거래 1건 삭제)
[GET /api/holdings] HoldingService.list()
   ├ findAll → groupBy(symbol)
   ├ getPrices/getStocks(distinct symbols)  (현재가·이름)
   ├ UniverseMapper.findSectorBySymbol, DailyOhlcvMapper.rangeSince(firstBuyDate)
   └ PositionCalc.of(trades, ...) → PositionView (trades[]=TradeView)
[POST /api/daily/backfill-symbol] (기존 재사용) ← 보유 화면 ↻백필 버튼
[holdings.html] side 토글 폼 + 집계표 + 펼침 + 백필버튼 + 합계
```

## 7. 함수 명세
| 함수 | 책임 | 복잡? |
|------|------|-------|
| `PositionCalc.of` | 거래목록→평균단가 집계+지표 | **복잡** → `fn-position-calc.md` |
| `HoldingService.list` | 그룹·보강 조립 | **복잡** |
| `HoldingController.add` | side 검증·조건부 백필 | 단순 |
| `HoldingMapper.*` | 원장 CRUD(side) | 단순 |

## 8. 엣지케이스
- SELL 인데 quantity 없음 → 400.
- 매도량 > 보유량 → 순수량 음수 허용(표시만, 막지 않음 — 수동원장).
- price 0/음수 → 400.
- 현재가 없음 → 미실현/스탑거리 '-', 스탑가는 표시.
- 수량 일부라도 null → 폴백(단순평균, amount '-').
- 종목 전량 매도(netQty=0) → 미실현 0/'-', 실현손익만 표시.
- 일봉 없음 → peak/trough null → MDD '-'.

## 9. 테스트 (PositionCalcTest)
- 매수1 → 단일 평단·미실현.
- 매수2(분할) → 가중 평단.
- 매수후 일부 매도 → 평단 불변·실현손익·순수량 감소.
- 전량 매도 → netQty 0, 실현손익만.
- 수량 null 폴백 → 단순평균·amount null.
- 스탑/MDD 부호·belowStop.

## 10. Open Questions
- (보류) 순수량 음수(과매도) 경고 UI — 이번엔 표시만.
- (보류) stopPct 를 포지션 단위 별도 설정으로 분리할지 — 현재는 최근 BUY 값 사용.
