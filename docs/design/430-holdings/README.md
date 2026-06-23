# 설계서: 내 보유 포트폴리오(수동 입력) (#430)

> **상태**: Approved · **작성**: [AI] Architect · 2026-06-23
> **추적성** — Redmine: #430 · 기반: 시세(prices)·#419(일봉)·섹터 · 구현: `holding/*`, `web/HoldingController.java`, `static/holdings.html`, `db/holding.sql`
> · 테스트: `HoldingCalcTest`

## 1. 목적
토스 계좌 연동 없이 **수동으로 보유 종목을 입력**(종목·매수일시·매수가·수량·손절%)하면, 현재가 기준 **수익률·평가손익·보유일·하드스탑가·스탑까지 여유·매수후 고점대비 낙폭**을 보여준다.

## 2. 범위
- **포함**: 보유 CRUD(추가/삭제), 현재가(prices) 보강, 지표 계산, `holdings.html` 표.
- **제외**: 실제 주문/연동, 분할매수 평단 자동(1행=1매수, 평단은 사용자가 입력), 세금·수수료.

## 3. 인수조건
- [ ] 추가: `symbol, buyAt, buyPrice, quantity?(선택), stopPct(기본 8), memo?`.
- [ ] 행별 표시: 종목·매수가·매수일·현재가·**수익률%**·평가손익(수량시)·보유일·**하드스탑가(=매수가×(1−N%))**·스탑까지 여유%·매수후 고점·**고점대비 낙폭(MDD)**.
- [ ] 현재가 ≤ 스탑가 → 🔴 손절 강조. 보유 중 저가가 스탑 터치한 적 있으면 표시.
- [ ] 현재가 조회 실패 종목은 수익률 등 '-' (스탑가는 매수가 기반이라 항상 계산).
- [ ] 합계: 총 평가손익(수량 있는 것), 평균 수익률.

## 4. 데이터 모델
- **HOLDING**: `id PK`, `symbol VARCHAR2(6)`, `buy_at TIMESTAMP`, `buy_price NUMBER`, `quantity NUMBER`(nullable), `stop_pct NUMBER`(기본8), `memo VARCHAR2(200)`, `created_at`.
- **HoldingView**(응답): 입력값 + `name,sector,currentPrice,returnPct,returnAmount,daysHeld,stopPrice,stopDistPct,belowStop,peakSinceBuy,ddFromPeak,stopHitSinceBuy`.

## 5. 계산 (순수, HoldingCalc)
```
stopPrice   = buy × (1 − stopPct/100)
returnPct   = cur? (cur−buy)/buy×100 : null
returnAmount= (cur,qty)? (cur−buy)×qty : null
stopDistPct = cur? (cur−stopPrice)/cur×100 : null     # 양수=여유, 음수=스탑이탈
belowStop   = cur!=null && cur ≤ stopPrice
daysHeld    = floor(now − buyAt, days)
ddFromPeak  = (peak,cur)? (peak−cur)/peak×100 : null   # 매수후 고점대비 현재 낙폭
stopHit     = trough!=null && trough ≤ stopPrice       # 보유중 스탑 터치 이력
```
- peak/trough = 매수일 이후 daily_ohlcv max(high)/min(low).

## 6. 아키텍처
```
[POST/DELETE /api/holdings] HoldingMapper (Oracle HOLDING)
[GET /api/holdings] HoldingService.list()
   ├ findAll → symbols
   ├ TossApiClient.getPrices(symbols)  (현재가, 1콜)
   ├ getStocks(symbols)(이름) + UniverseMapper.findSectorBySymbol(섹터)
   ├ DailyOhlcvMapper.rangeSince(symbol, buyDate) → peak/trough (보유별)
   └ HoldingCalc.of(...) → HoldingView
[holdings.html] 폼 + 표 + 합계
```

## 7. 함수 명세
| 함수 | 책임 | 복잡? |
|------|------|-------|
| `HoldingCalc.of` | 입력+현재가+peak/trough → 지표 | **복잡** |
| `HoldingService.list` | 보강 조립 | **복잡** |
| `HoldingMapper.*` | CRUD | 단순 |
| `DailyOhlcvMapper.rangeSince` | 매수후 고저 | 단순 |

> 복잡 함수 → `fn-holding-calc.md`.

## 8. 엣지케이스
- buyPrice 0/음수 → 거부(400).
- quantity 없음 → 평가손익 '-', 수익률만.
- 현재가 없음 → 수익률/스탑거리 '-', 스탑가는 표시.
- 매수일 이후 일봉 없음 → peak/trough null → MDD '-'.
- stopPct 0 → 스탑가=매수가.

## 9. 테스트
- `HoldingCalcTest`: 수익/손실 returnPct, stopPrice, stopDistPct 부호, belowStop, ddFromPeak, daysHeld, null 처리.

## 10. Open Questions
- 트레일링 스탑(고점기준 −N%) 옵션은 후속.
- 분할매수 평단 자동집계는 후속.
