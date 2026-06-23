# 설계서: 국내 ETF 검색 시드 (#434)

> **상태**: Approved · **작성**: [AI] Architect · 2026-06-24
> **추적성** — Redmine: #434 · 기반: #432(종목 검색)·#418(universe) · 구현: `scripts/seed-etf.py`, `src/main/resources/universe-krx.json`

## 1. 목적
종목 검색 소스(`universe-krx.json`)는 KRX 상장**법인**(주식) 마스터라 **ETF가 누락**되어 KODEX/TIGER 등이 검색되지 않는다. 국내 ETF 전종목을 `universe` 에 시드해 검색·등록 가능하게 한다.

## 2. 범위
- **포함**: 국내 ETF(코드·종목명) 수집 → `universe`(market='ETF') 시드 + `universe-krx.json` 갱신, 멱등 생성기.
- **제외**: ETF NAV/구성종목, 해외 ETF, 일봉 자동수집(필요 시 종목별 백필로).

## 3. 인수조건
- [ ] KODEX/TIGER 등 국내 ETF 가 `/api/search` 에 노출(`069500 KODEX 200` 등).
- [ ] `universe.market='ETF'` 로 구분, **한글명 정상 저장**.
- [ ] 재실행해도 중복/깨짐 없음(멱등).

## 4. 데이터 소스 / 결정
- **소스**: 네이버 금융 ETF 목록 API `finance.naver.com/api/sise/etfItemList.nhn`.
  - KRX 정보데이터시스템(OTP/CSV)은 이 환경에서 차단(403/LOGOUT) → 대체.
  - 응답 charset = **EUC-KR(cp949)**. utf-8 로 디코딩하면 한글 깨짐 → `cp949` 로 디코딩.
- **적재 인코딩 함정 회피**: sqlcl 의 `@script` 파일 charset 의존으로 한글이 깨질 수 있어, 한글을 **`UNISTR('\XXXX')` ASCII 이스케이프**로 생성 → `.sql` 을 순수 ASCII 로 만들어 원천 차단.
- **충돌 방지**: ETF 코드(6자리)는 주식 코드와 겹치지 않음. `market='ETF'` 는 이 시드 전용 → 재적재 시 선삭제로 멱등.

## 5. 아키텍처
```
scripts/seed-etf.py
  ├ fetch(네이버, cp949) → [{symbol,name,market:'ETF'}] (~1140)
  ├ etf-seed.sql  : SET DEFINE OFF; DELETE WHERE market='ETF'; INSERT ALL(배치100) ; COMMIT
  │                 (name = UNISTR ASCII 이스케이프)
  └ universe-krx.json : 기존 ETF 제거 후 재추가(주식 엔트리 보존)
sqlcl @etf-seed.sql → universe 적재
UniverseSeeder (앱) : universe 비었을 때 universe-krx.json 으로 시드(ETF 포함)
```

## 6. 엣지케이스
- 네이버 일시 오류 → 생성 실패(부분 적재 없음, DELETE 후 INSERT ALL 트랜잭션).
- 종목명에 `&`/`'` → SET DEFINE OFF + UNISTR 내 `''` 이스케이프.
- 이미 시드됨(universe 비어있지 않음) → 앱 시더는 스킵, 수동 `@etf-seed.sql` 로 반영.

## 7. 검증
- `/api/search?q=069500` → KODEX 200, `q=2차전지`/`q=미국` → 한글 ETF 정상.
- `universe` 2605 → 3745 (ETF 1140), `market='ETF' AND name LIKE '%?%'` = 0.

## 8. Open Questions
- (보류) ETF 를 야간 전수 일봉 스캔(`runScan`)에서 제외할지 — 현재 포함(수십 MB 수준).
- (보류) 정기 갱신(신규 상장 ETF) 스케줄링 — 현재 수동 `seed-etf.py`.
