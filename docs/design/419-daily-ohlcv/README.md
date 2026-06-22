# 설계서: 전체 시장 일봉 OHLCV 수집/저장 (1년, 계층1) (#419)

> **상태**: Approved
> **작성**: [AI] Architect · **최종수정**: 2026-06-22
> **추적성** — Redmine: #419 · 관련 ADR: ADR-0006(2계층 수집기) · 대체: #416 탑50 live 스캔
> · 구현: `market/DailyOhlcv*`, `market/DailyCollector.java`, `db/daily_ohlcv.sql`
> · 테스트: `DailyCollectorTest`

## 1. 목적 (Why)
시장 전체(2,605종목)의 **일봉 OHLCV 1년치**를 모아 저장한다. 이게 거래량 랭킹·차트·후속 분석의 단일 원천(계층1)이 된다. 분봉은 저장하지 않는다(라이브 조회는 후속).

## 2. 범위
- **포함**:
  - `daily_ohlcv` 테이블(종목×거래일 OHLCV).
  - **백필**: 종목별 ~1년(≈250거래일) 일봉 적재(count≤200 + `before` 페이지네이션, 2콜/종목).
  - **일일 갱신**: 매 영업일 마감 후 count=2 로 당일/전일 upsert(1콜/종목).
  - **탑50 전환**: 거래량 탑50을 `daily_ohlcv` 최신일 기준 쿼리로 산출(+섹터+라이브 현재가). 기존 live 전종목 스캔(#416) 대체.
- **제외**: 분봉 저장, 시간봉(토스 미지원), 종목 상세/차트 UI(후속), 멀티 인스턴스.

## 3. 인수조건
- [ ] `daily_ohlcv(symbol, trade_date)` PK, OHLCV 저장.
- [ ] `POST /api/daily/backfill` → 백그라운드 1년 백필(중복 방지, 진행률).
- [ ] `POST /api/daily/refresh`(및 평일 15:40) → 당일 일봉 upsert.
- [ ] 토스 제약 준수: 1d, count≤200, `before=<ISO>` 페이지네이션, 레이트리밋 스로틀+429 백오프.
- [ ] `GET /api/top50` 가 `daily_ohlcv` 최신일 거래량 상위 50(섹터 포함) 반환.
- [ ] 백필/갱신 재실행 멱등(upsert).

## 4. 컨텍스트 & 제약 (실측)
- 토스 캔들: `interval` ∈ {1m, 1d} 만. `count` 1..200. 페이지네이션 `before=<직전 응답 nextBefore(ISO+TZ)>`.
- 레이트리밋: ~25 버스트/10초, 지속 ~10콜/s → 스로틀 90ms + 429 백오프.
- 백필 ≈ 2콜×2,605 ≈ 5,210콜 ≈ 26분(1회). 일일갱신 ≈ 1콜×2,605 ≈ 13분.
- 의존: 토스 `candles(1d)`, `prices`(탑50 현재가), `UNIVERSE`(섹터원천), Oracle.

## 5. 아키텍처
```
[POST /api/daily/backfill]  (1회)         [POST /api/daily/refresh] / @Scheduled(평일 15:40)
        └ backfillYear(symbol)                    └ refreshLatest(symbol, count=2)
            candles(1d,200) + before 페이지              candles(1d,2)
            → upsert daily_ohlcv                         → upsert daily_ohlcv
                         │
[GET /api/top50] ─ SELECT daily_ohlcv 최신일 ORDER BY volume DESC FETCH 50
                  + UNIVERSE(sector/name/market) + prices(현재가)
                  → top50.html
```
- **경계**: 캔들→행 변환·랭킹은 순수 로직(`toDaily`, 정렬). 스캔 루프·DB·HTTP는 I/O.

## 6. 데이터 모델
- `DAILY_OHLCV`: `symbol VARCHAR2(6)`, `trade_date DATE`, `open/high/low/close NUMBER`, `volume NUMBER`, PK(`symbol`,`trade_date`).
- upsert: `MERGE`(존재 시 갱신, 없으면 삽입).
- 탑50 응답: 기존 `VolumeRank`(sector 포함) 재사용 — 단 출처가 daily_ohlcv 쿼리.

## 7. 함수 명세
| 함수 | 책임 | 시그니처 | 복잡? |
|------|------|----------|-------|
| `DailyCollector.backfillYear` | 전종목 1년 백필(async) | `void backfillYear()` | **복잡** |
| `DailyCollector.fetchYear` | 종목 1년 일봉(페이지네이션) | `List<TossCandle> fetchYear(symbol)` | **복잡** |
| `DailyCollector.refreshLatest` | 전종목 당일 upsert(async) | `void refreshLatest()` | **복잡** |
| `DailyOhlcvMapper.upsert` | MERGE 1행 | `int upsert(DailyOhlcv)` | 단순 |
| `DailyOhlcvMapper.topByVolumeOnLatest` | 최신일 거래량 상위N | `List<...> top(int n)` | 단순 |
| `Top50Service.assemble` | 탑50 쿼리+섹터+현재가 | `List<VolumeRank>` | **복잡** |

> 복잡 함수 → `fn-daily-collector.md`.

## 8. 흐름
- **백필**: universe 순회 → fetchYear(count200 → nextBefore로 before 페이지, 1년 컷) → 각 캔들 upsert. 진행률.
- **갱신**: universe 순회 → candles(1d,2) → 당일·전일 upsert.
- **탑50 조회**: 최신 trade_date 의 거래량 desc 50 → universe join(sector) → prices 현재가 보강.

## 9. 엣지케이스
- 신규상장/거래정지: 캔들 적으면 가능분만 저장, 스킵.
- 페이지네이션 종료: nextBefore 없거나 1년 경계 도달 시 중단.
- 휴장일: 캔들 없으면 그날 행 없음(정상).
- upsert 멱등: 같은 (symbol,date) 재적재 시 갱신.
- 부분 실패: 종목 단위 스킵, 전체 계속.

## 10. 테스트
- 단위(`DailyCollectorTest`): toDaily 변환, 1년 컷 로직, top 정렬(거래량 desc). 페이지네이션 종료 조건.
- 실측: backfill 일부/refresh 후 daily_ohlcv 건수, /api/top50 섹터 포함 확인.

## 11. 리스크 & 대안
- 백필 26분: 1회성, 허용. 대안(폐기): 외부 일봉 벌크 소스(별도 인증/포맷).
- 저장량: 2,605×250 ≈ 65만행. Oracle 충분. 보관은 1년 롤링(후속 정리).
- 탑50 출처 전환: 기존 live 스캔 제거 → 중복 호출 제거(이득).

## 12. Open Questions
- 백필 자동 트리거(최초 1회) vs 수동.
- 1년 경계 정확 컷(거래일 250 vs 캘린더 365일).
- 분봉 라이브 조회(계층2) 착수 시점.
