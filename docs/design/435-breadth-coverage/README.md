# 설계서: 시장 상승비율(breadth) 기준일 보정 (#435)

> **상태**: Approved · **작성**: [AI] Architect · 2026-06-24 · **유형**: 결함 수정
> **추적성** — Redmine: #435 · 기반: #429(마켓 배너/상승비율)·#419(일봉) · 구현: `mapper/DailyOhlcvMapper.xml`, `market/DailyOhlcvMapper.java`, `web/MarketOverviewController.java`, `static/top50.html`

## 1. 증상
시장 상승비율 배너가 `82% 상승장 (9/11 종목)` 처럼 **11종목**만으로 계산. 시장 전체(수백~수천)와 동떨어짐.

## 2. 원인
`breadth` 쿼리가 **전역 `MAX(trade_date)`** 기준으로 그 날짜에 일봉이 있는 종목끼리만 비교. 그런데 보유 등록·미국주식·ETF의 **수동 단일 종목 백필**이 최근 날짜(06-23/24)에 11~15종목만 채워 `MAX(trade_date)`를 끌어올림 → 전수 스캔이 채운 06-22(649종목)가 아니라 수동 백필 11종목이 표본이 됨.

## 3. 수정
- 일봉 종목수가 **`minCoverage`(=100) 이상**인 거래일만 "기준일 후보(covered)"로 인정.
- 기준일 = covered 중 최신, 전일 = 기준일 직전의 covered. 둘에 모두 존재하는 종목으로 상승 집계.
- 응답에 `breadthAsOf`(기준일)·`breadthPrev`(전일) 추가, 배너에 기준일 표기.
- 100 임계는 전수 스캔(300~650) vs 수동 백필(1~15)을 명확히 가름.

## 4. 쿼리 (Oracle, CTE)
```sql
WITH covered AS (
  SELECT trade_date FROM daily_ohlcv GROUP BY trade_date HAVING COUNT(*) >= :minCoverage
),
ref AS (SELECT MAX(trade_date) d FROM covered),
prv AS (SELECT MAX(trade_date) d FROM covered WHERE trade_date < (SELECT d FROM ref)),
agg AS (   -- 집계는 별도 CTE (스칼라 서브쿼리와 혼용 시 ORA-00937 회피)
  SELECT SUM(CASE WHEN t.close_p > p.close_p THEN 1 ELSE 0 END) up, COUNT(*) total
  FROM daily_ohlcv t JOIN daily_ohlcv p ON p.symbol = t.symbol
  WHERE t.trade_date = (SELECT d FROM ref) AND p.trade_date = (SELECT d FROM prv)
)
SELECT TO_CHAR((SELECT d FROM ref),'YYYY-MM-DD') ref_date,
       TO_CHAR((SELECT d FROM prv),'YYYY-MM-DD') prev_date, up, total
FROM agg
```

## 5. 엣지케이스
- covered 가 1일뿐/없음 → prv null → 비교 0행 → up null/total 0 → 배너 `breadthPct=0`, total>0 가드로 안전.
- 전수 스캔이 아직 100 미만인 초기 → 가장 최근 충분일 사용(없으면 표시 안 함).

## 6. 검증 (라이브)
- 수정 전: `MAX=06-24(11종목)` → 82%.
- 수정 후: `06-22 vs 06-19, 112/644 = 17%(하락장)`, `breadthAsOf=2026-06-22`.

## 7. Open Questions
- (보류) 임계 100을 "최근 N일 최대 커버리지의 50%" 같은 상대값으로 자동화할지 — 현재 고정 상수(`MarketOverviewController.BREADTH_MIN_COVERAGE`).
- (별개) 06-23 전수 스캔 누락 — 스케줄러 가동/재기동 운영 이슈. 데이터 수집과 별도.
