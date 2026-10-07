# 설계서: 대시보드 날짜별 실현손익 breakdown (#851)

> **상태**: Approved
> **작성**: [AI] Architect · **최종수정**: 2026-10-08
> **추적성** — Redmine: #851(기반 #849) · 관련 ADR: 없음
> · 구현 파일: `AutoTradePositionMapper`/`.xml`, `RangeTradePositionMapper`/`.xml`(신규 쿼리),
>   `DailyRealizedPnl.java`(신규, autotrade 패키지 — range도 재사용),
>   `AutoTradeController`/`RangeTradeController`(summary에 dailyBreakdown 추가),
>   `static/autotrade.html` · 테스트: 컨트롤러 테스트 확장

## 1. 목적 (Why)
사용자가 토스 앱의 일별 보기(예: "10월 7일(수) 국내주식 -234,305원")와 대시보드의 "누적
실현손익"(전체 기간 합계, -200,805원)을 직접 비교하다 숫자가 안 맞는다고 느낌 — 실제로는
전체 누적에 다른 날짜(10/2, +33,500) 거래가 섞여 있어 스코프가 다를 뿐 둘 다 정확했음(검증
완료: 10/7만 떼면 -234,305로 정확히 일치). 토스 앱처럼 날짜별로 쪼개 보여줘서 이 혼동을
구조적으로 없앤다.

## 2. 범위 (Scope)
- **포함**: EXITED 포지션을 `exit_at` 날짜별로 그룹핑한 표(날짜, 매매건수, 가격차손익,
  수수료, 순손익)를 `/status` 응답(`summary.dailyBreakdown`)에 추가하고 대시보드에 표로 렌더.
- **제외 (out of scope)**: 날짜 범위 필터 UI(전체 기간 고정 — 필요해지면 후속), 요일/주간
  집계, 미국 시장 타임존 보정(현재 전부 KR 종목만 거래 중이라 범위 밖).

## 3. 인수조건 (Acceptance Criteria)
- [ ] `/status`의 `summary.dailyBreakdown`이 날짜 내림차순으로 `{exitDate, trades, grossPnl,
      fees, netPnl}`을 반환한다.
- [ ] 같은 날짜의 여러 포지션이 올바르게 합산된다(기존 `realizedFeesTotal`과 동일한 "각
      포지션 자신의 BUY/SELL 로그"매칭 방식 재사용 — 블랭킷 합산 버그 재발 금지).
- [ ] 대시보드에 날짜별 표가 보이고, 합계가 기존 "누적 실현손익" 카드와 일치한다(크로스체크).
- [ ] 기존 `summary` 키(totalTrades 등)는 그대로 유지(하위호환).

## 4. 컨텍스트 & 제약
- 의존성: 기존 `auto_trade_position`/`auto_trade_order_log`(레인지는 대응 테이블) — 신규
  스키마 변경 없음.
- 제약: 없음(읽기전용 집계 쿼리 1개 추가).

## 5. 아키텍처 개요
```
GET /api/autotrade/status
  └─ summary.dailyBreakdown: [
       {exitDate:"2026-10-07", trades:48, grossPnl:-169640, fees:64665, netPnl:-234305},
       {exitDate:"2026-10-02", trades:1,  grossPnl:33500,   fees:0,    netPnl:33500}
     ]
```
I/O ↔ 순수 로직: SQL이 날짜별 집계까지 전부 수행(단순 조회, 순수 가공 로직 없음 — Java
단에서 추가 계산 없이 그대로 직렬화).

## 6. 데이터 모델
`DailyRealizedPnl(String exitDate, int trades, BigDecimal grossPnl, BigDecimal fees, BigDecimal netPnl)`
— `exitDate`는 `TO_CHAR(TRUNC(exit_at),'YYYY-MM-DD')`(타임존 이슈 없이 문자열로 직접 비교
가능하게).

## 7. 함수 명세 (Function Specs)

| 함수 | 책임 | 시그니처 | 복잡? |
|------|------|----------|-------|
| `AutoTradePositionMapper.dailyRealizedSummary` | 날짜별 매매건수/가격차손익/수수료/순손익 집계 | `List<DailyRealizedPnl> dailyRealizedSummary()` | 단순(SQL이 전부 수행) |
| `RangeTradePositionMapper.dailyRealizedSummary` | 동일(레인지) | 상동 | 단순 |

## 8. 흐름 / 알고리즘
`realizedFeesTotal()`과 동일한 포지션별 BUY/SELL own-leg 매칭을 `GROUP BY TRUNC(exit_at)`
안쪽 서브쿼리에서 재사용 — 전체 집계 로직은 바뀌지 않고 그룹 기준만 추가됨.

## 9. 엣지케이스 & 에러 처리
- EXITED 포지션이 0건 → 빈 리스트(에러 아님).
- 특정 날짜의 포지션에 매칭되는 주문로그가 없으면(§850 이전 데이터, entry/exit 로그가 날짜
  범위 밖) 그 포지션의 fee 기여분은 0으로 집계(기존 `realizedFeesTotal`과 동일한 안전장치).

## 10. 테스트 계획
- `AutoTradeControllerTest`/`RangeTradeControllerTest`: `summary.dailyBreakdown` 키 존재·
  값 검증(Mockito 스텁).
- 수동: 배포 후 날짜별 합계가 기존 누적 카드와 일치하는지, 10/7 행이 -234,305인지 확인.

## 11. 리스크 & 대안 검토
- 대안(기각): 프론트에서 기존 `/orderlog` 페이지네이션 데이터를 날짜별로 그룹핑 — 주문로그는
  BUY/SELL 각각 별도 행이라 "포지션 단위 순손익"을 다시 조립해야 해서 더 복잡. 포지션 테이블
  기준 집계가 더 직접적이고 기존 `realizedFeesTotal` 로직을 그대로 재사용 가능.
- 리스크: 낮음 — 읽기전용 집계, 기존 검증된 매칭 로직 재사용.

## 12. 미해결 질문 (Open Questions)
- 없음.
