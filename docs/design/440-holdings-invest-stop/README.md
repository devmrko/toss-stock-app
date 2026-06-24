# 설계서: 보유 투자금액 표시 + 손절% 수정 (#440)

> **상태**: Approved · **작성**: [AI] Architect · 2026-06-25
> **추적성** — Redmine: #440 · 기반: #433(보유) · 구현: `holding/HoldingMapper(.xml)`, `web/HoldingController`, `static/holdings.html`

## 1. 목적
내 보유 화면에 **투자금액(각 종목·전체)** 을 보여주고, **종목별 손절%** 를 인라인으로 수정한다.

## 2. 인수조건
- [ ] 각 종목 **투자금액 = 순수량 × 평단**(매입원가) 컬럼, 합계 **총 투자금액**.
- [ ] 손절% 셀 클릭 → 입력 → 그 종목 손절% 변경 → 손절가/지표 즉시 갱신.
- [ ] 0~100 외 값 거부.

## 3. 설계
### 3.1 투자금액(프론트 계산, API 무변경)
- `inv = (netQty>0 && avgCost) ? round(netQty × avgCost) : null`. 합계 = Σ inv. `PositionView` 의 netQty/avgCost 사용.

### 3.2 손절% 수정
- `PUT /api/holdings/stop?symbol=&stopPct=` → `HoldingMapper.updateStopPctBySymbol` 로 **그 종목 전 거래의 stop_pct 일괄 변경**.
- `PositionCalc` 는 최근 BUY 의 stopPct 를 쓰므로 즉시 반영. 0~100 검증(400).
- UI: `.stoped`(스탑% 표기) 클릭 → `prompt` → PUT → `load()`.

## 4. 엣지케이스
- 청산(순수량 0)/폴백(netQty null) → 투자금액 '-'.
- 종목 거래 다수 → 전부 동일 stop_pct 로 통일(포지션 단위 의미).
- stopPct 0 → 손절가=평단.

## 5. 검증(라이브)
- PUT 204(10→12, 손절가 재계산), 150 → 400. 페이지 투자금액·총합·수정 UI 정상.

## 6. Open Questions
- (보류) 익절% 포지션별 컬럼(현재 익절 표현 없음).
