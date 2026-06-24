# 설계서: 보유 매매처(증권사) 등록 (#441)

> **상태**: Approved · **작성**: [AI] Architect · 2026-06-25
> **추적성** — Redmine: #441 · 기반: #433(거래원장) · 구현: `db/holding.sql`, `holding/{Holding,PositionView,TradeView,PositionCalc}`, `mapper/HoldingMapper.xml`, `web/HoldingController`, `static/holdings.html`, `briefing/BriefingFormatter` · 테스트: `PositionCalcTest`

## 1. 목적
거래(매수/매도)마다 **매매처(증권사/계좌)** 를 등록하고, 보유 화면·브리핑에 표시한다. ("어디서 매매하는지")

## 2. 인수조건
- [ ] 추가 폼에 **매매처** 입력(자유 입력 + 흔한 증권사 추천 datalist), 선택값.
- [ ] 거래내역(펼침)에 거래별 매매처 표시.
- [ ] 종목 집계행에 **매매처(중복 제거)** 표시(여러 곳이면 콤마).
- [ ] 브리핑 보유 라인에 매매처 표기(있을 때).

## 3. 데이터 모델
- **HOLDING**: `broker VARCHAR2(40)`(신규, nullable) — 거래 단위. 멱등 ALTER.
- **TradeView**: `broker` 추가.
- **PositionView**: `broker`(종목의 distinct 매매처 콤마결합) 추가.

## 4. 설계
- `PositionCalc.of`: TradeView 에 broker 매핑, 포지션 broker = 거래들의 비어있지 않은 매매처 distinct(입력순) 콤마결합. 시그니처 변경 없음(거래에서 도출).
- `HoldingController.add`: `AddRequest.broker` → `Holding.broker`.
- UI: 폼 input + `<datalist>`(토스·키움·미래에셋·삼성·NH·KB·한국투자·신한 등), 집계행 종목셀에 매매처 뱃지, 거래행에 매매처.
- 브리핑: 보유 라인 끝에 `· {broker}`(있을 때).

## 5. 엣지케이스
- 매매처 미입력 → null, 표시 생략.
- 한 종목 여러 매매처 → 콤마결합.

## 6. 테스트
- `PositionCalcTest`: TradeView/PositionView broker 도출(여러 거래 distinct).

## 7. Open Questions
- (보류) 매매처별 손익 집계/필터 — 이번엔 표시만.
