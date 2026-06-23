# 설계서: 컬럼 헤더 클릭 정렬 (#428)

> **상태**: Approved · **작성**: [AI] Architect · 2026-06-23
> **추적성** — Redmine: #428 · 구현: `static/top50.html`, `static/watchlist.html` (프론트 전용)

## 1. 목적
탑50/워치리스트 표의 **컬럼 헤더를 클릭**하면 그 컬럼으로 정렬(오름↑/내림↓ 토글)된다.

## 2. 범위
- **포함**: 정렬 가능한 헤더(순위·종목·현재가·등락률·거래량), 클릭 토글, 활성 컬럼 화살표(▲▼), 폴링 재렌더에도 정렬 유지.
- **제외**: 백엔드 정렬(클라이언트에서 처리), 다중 컬럼 정렬.

## 3. 인수조건
- [ ] 헤더 클릭 시 해당 키로 정렬. 같은 헤더 재클릭 → 방향 토글.
- [ ] 기본: 거래량 내림차순(서버 순서와 동일).
- [ ] 숫자(현재가·등락률·거래량·순위)는 수치 정렬, null 은 맨 뒤. 종목은 가나다.
- [ ] 5초/3초 폴링이 정렬 상태를 유지(최근 데이터 재정렬).
- [ ] 워치리스트 삭제(✕) 헤더는 정렬 비대상.

## 4. 설계
- 전역 `sortKey`,`sortDir(1/-1)`. `render(rows)` 가 `sortRows(rows)` 후 렌더.
- `lastRows` 보관 → 헤더 클릭 시 refetch 없이 `render(lastRows)`.
- 비교: name=localeCompare, 그 외 Number(null→-Infinity)로 수치 비교 × sortDir.
- 키: top50 `rnk/name/lastPrice/changeRate/volume`, 워치리스트 `rank/...`.

## 5. 엣지케이스
- null 등락률/거래량 → 맨 뒤(내림)·맨 앞 회피(-Infinity).
- 정렬 중 새 폴링 데이터 → 동일 sortKey/Dir로 재정렬(깜빡임 최소).

## 6. Open Questions
- 정렬 상태 URL/localStorage 영속(후속).
