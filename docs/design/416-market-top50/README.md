# 설계서: 오늘 거래량 시장전체 탑50 (일배치) (#416)

> **상태**: Approved
> **작성**: [AI] Architect · **최종수정**: 2026-06-22
> **추적성** — Redmine: #416 · 관련 ADR: ADR-0003(유니버스), ADR-0004(일배치 스캔)
> · 구현: `market/*`, `web/Top50Controller.java`, `resources/static/top50.html`, `resources/universe-krx.json`
> · 테스트: `Top50ServiceTest`, (실측) 첫 스캔

## 1. 목적 (Why)
"오늘 거래량 기준 시장 전체 탑50"을 본다. 토스엔 랭킹/목록 API가 없으므로 **KRX 전체 유니버스를 직접 스캔**해 거래량 순위를 산출·저장한다.

## 2. 범위 (Scope)
- **포함**: KRX 전체(KOSPI+KOSDAQ ≈2,605) 유니버스 시드, 1일1회(및 수동) 거래량 스캔→탑50 저장, 조회 API, `top50.html`.
- **제외**: 분/시간봉 실시간 랭킹, ETF/ETN/우선주 필터링 정교화(코드 6자리면 일단 포함), 거래대금 기준(이번은 거래량).

## 3. 인수조건
- [ ] 앱 최초 기동 시 `universe` 가 비어 있으면 `universe-krx.json`(2,605건)으로 시드.
- [ ] `POST /api/top50/refresh` → 백그라운드 스캔 시작(202), 중복 실행 방지.
- [ ] 스캔: 레이트리밋(~25버스트/10초) 대응 스로틀+429 백오프, 전 종목 거래량 수집.
- [ ] 거래량 desc 탑50 산출, `volume_rank` 에 `as_of` 와 함께 저장(이전 결과 교체).
- [ ] `GET /api/top50` → `{status, asOf, scanned, total, rows[50]}`.
- [ ] `top50.html`: 순위·종목·시장·현재가·등락률·거래량 표, 진행률/as-of, 새로고침 버튼.
- [ ] 1일 1회 스케줄(장 마감 후) 자동 갱신.

## 4. 컨텍스트 & 제약
- **레이트리밋(실측)**: 버스트 ~25콜 후 429, 지속 ~10콜/s. → 호출 간 ~110ms 슬립, 429 시 백오프(1.5s, 최대 3회 재시도).
- **스캔 시간**: 2,605종목 ≈ 5분 → **백그라운드 + 상태 폴링**. 페이지는 저장된 결과 즉시 표시.
- **의존**: 토스 `candles(count=1)`(거래량), 탑50 보강용 `prices`(현재가)·`candles(count=2)`(전일종가). KRX corpList(시드 1회). Oracle.

## 5. 아키텍처
```
[startup] UniverseSeeder ──(universe 비면)──> universe-krx.json → UNIVERSE 테이블
[POST /api/top50/refresh] / [@Scheduled 일1회]
        └─> Top50Service.refresh() @Async ─ ScanStatus(in-memory: RUNNING/DONE, scanned/total, asOf)
              │  for each symbol: throttle+backoff → TossApiClient.getDailyCandles(count=1).volume
              │  sort desc, take 50
              │  top50 보강: prices(현재가) + candles(count=2 전일종가→등락률)
              ▼
        VOLUME_RANK 테이블(as_of, rnk, symbol, name, market, volume, last_price, prev_close, change_rate)
[GET /api/top50] ─> {status, asOf, scanned, total, rows} ─> top50.html (폴링 3s)
```
- **I/O ↔ 순수 로직 경계**: 정렬/탑50 컷/등락률은 순수 메서드(`rankTop50`, `toRankRow`)로 분리해 단위 테스트. 스캔 루프·DB·HTTP는 I/O.

## 6. 데이터 모델
- **UNIVERSE**: `symbol VARCHAR2(6) PK`, `name VARCHAR2(100)`, `market VARCHAR2(10)`.
- **VOLUME_RANK**: `as_of TIMESTAMP`, `rnk NUMBER`, `symbol`, `name`, `market`, `volume NUMBER`, `last_price NUMBER`, `prev_close NUMBER`, `change_rate NUMBER`. (rank=예약어 → `rnk`)
- **ScanStatus**(메모리): `state(IDLE|RUNNING|DONE|ERROR)`, `scanned`, `total`, `asOf`, `startedAt`, `message`.
- **Top50Response**(DTO): status 메타 + `List<RankRow>`.

## 7. 함수 명세
| 함수 | 책임 | 시그니처 | 복잡? |
|------|------|----------|-------|
| `UniverseSeeder.run` | 비면 JSON 시드 | `void run(args)` | 단순 |
| `Top50Service.refresh` | 전종목 스캔→탑50 저장(async) | `void refresh()` | **복잡** |
| `Top50Service.scanVolumes` | 스로틀+백오프 거래량 수집 | `List<SymVol> scanVolumes(List<Universe>)` | **복잡** |
| `Top50Service.rankTop50` | 거래량 desc 상위50 | `List<SymVol> rankTop50(List<SymVol>)` | 단순 |
| `Top50Service.enrich` | 탑50 현재가/등락률 보강 | `List<RankRow> enrich(List<SymVol>)` | **복잡** |
| `Top50Controller.get/refresh` | 조회/트리거 | - | 단순 |

> 복잡 함수 → `fn-scan-batch.md`.

## 8. 흐름
1. 기동: universe 시드(1회).
2. refresh 트리거(수동/스케줄) → RUNNING, total=universe 수.
3. 종목별 candles(count=1) 거래량 수집(throttle 110ms; 429→백오프). scanned 증가.
4. 거래량 desc 정렬, 상위 50.
5. 상위 50 보강: prices(현재가 1~2콜), candles(count=2)로 전일종가→등락률.
6. VOLUME_RANK 교체 저장, asOf=now, DONE.
7. 페이지 폴링으로 진행률→완료 결과 표시.

## 9. 엣지케이스 & 에러
- 종목 일시 실패/상폐/거래정지 → 그 종목 스킵(거래량 0/누락), 전체 계속.
- 429 연속 → 백오프 후 재시도, 초과 시 해당 종목 스킵.
- 스캔 중 재트리거 → 무시(이미 RUNNING).
- 첫 조회(데이터 없음) → status IDLE/EMPTY, 페이지 "갱신을 눌러 집계" 안내.
- 부분 완료 후 에러 → 마지막 성공 결과 유지(교체 저장은 성공 시에만).

## 10. 테스트 계획
- **단위(`Top50ServiceTest`)**: rankTop50 정렬·50컷, toRankRow 등락률, 거래량 null 처리.
- **실측**: 첫 refresh 후 `GET /api/top50` 으로 상위 50 확인(삼성·SK 등 대형주 + KOSDAQ 급등주 포함 여부).

## 11. 리스크 & 대안
- 스캔 5분: 일배치라 허용. 대안(폐기): 실시간 랭킹=API 없음.
- 거래량 vs 거래대금: 이번은 거래량(요청). 거래대금이 더 의미 있을 수 있음 → 후속.
- 유니버스 노이즈(ETF/우선주/스팩): 코드 6자리 전부 포함 → 후속 필터.
- 동시성: 단일 인스턴스, ScanStatus 메모리. 멀티 인스턴스 시 분산락 필요.

## 12. Open Questions
- 스케줄 시각(장 마감 15:30 후 → 15:40 KST?).
- 거래대금 기준 옵션 추가 여부.
- 우선주/ETF 제외 필터 기준.
