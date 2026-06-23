# 설계서: 종목 검색 등록 + 미국주식 (#432)

> **상태**: Approved · **작성**: [AI] Architect · 2026-06-24
> **추적성** — Redmine: #432 · 기반: #419(UNIVERSE)·#414(워치)·#430(보유)
> · 구현: `search/*`, `web/SearchController.java`, `db/us_universe.sql`, `static/watchlist.html`·`holdings.html`
> · 테스트: 검색 쿼리(실측), 프론트 수동

## 1. 목적
종목코드를 몰라도 **이름으로 검색해 등록**한다. **미국주식**도 등록 가능(토스가 티커로 시세·일봉 제공 확인).

## 2. 범위
- **포함**: `/api/search?q=`(KR=UNIVERSE 2,605 + US=us_universe 12,445 통합), 워치리스트·보유 폼을 검색박스(자동완성)로.
- **제외**: 미국 일봉 수집(top50/지표는 KR만), 한국 뉴스 외 US 뉴스.

## 3. 컨텍스트(실측)
- 토스 미국주식: `prices/candles/stocks?symbols=AAPL` 정상(USD), KR+US 혼합 호출 OK. **검색 API 없음**(404).
- US 마스터: NASDAQ 심볼파일(nasdaqlisted+otherlisted) → 테스트제외·토스호환심볼 → 12,445.

## 4. 인수조건
- [ ] `GET /api/search?q=삼성` → 한국 매칭, `q=apple` → 미국 매칭. 코드 정확/접두 우선, 이름 포함.
- [ ] 결과 `{symbol,name,market,sector}` (US sector=null).
- [ ] 워치리스트/보유 추가 폼: 검색 입력 → 드롭다운 → 선택 시 symbol 확정. 직접 코드입력도 허용.
- [ ] 미국 종목 등록 시 현재가(USD)·수익률 등 정상(일봉기반 지표는 KR만).

## 5. 데이터/쿼리
- `US_UNIVERSE(symbol VARCHAR2(12) PK, name VARCHAR2(150), market)`.
- 검색: UNION(universe, us_universe) WHERE UPPER(name) LIKE %q% OR UPPER(symbol) LIKE q% ; ORDER BY (코드정확0/접두1/이름2), LENGTH(name) ; FETCH n.

## 6. 함수 명세
| 함수 | 책임 | 복잡? |
|------|------|-------|
| `SearchMapper.search` | KR+US 통합 검색+랭킹 | 단순(SQL) |
| `SearchController.search` | q 검증, 위임 | 단순 |
| (front) 자동완성 | 디바운스 fetch→드롭다운→선택 | 단순 |

## 7. 엣지케이스
- q 공백 → 빈 결과.
- 토스 미지원 US 종목 등록 → 추가는 되나 현재가 '-'(폴백).
- 동일코드 KR/US 충돌 없음(KR=숫자6, US=영문).

## 8. Open Questions
- US 섹터/일봉 수집(지표·top50 편입) 후속.
- 검색 인덱스(name) 추가 — 12k면 현재는 풀스캔도 빠름.
