# 설계서: 후보 발굴 중복제거 순서 교정 (#878)

> **상태**: Approved · **작성**: [AI] Architect · 2026-10-08
> **추적성** — Redmine: #878 · 구현: `CandidateDiscoveryService`,
>   `StockNewsMapper.xml` · 테스트: `CandidateDiscoveryServiceTest`

## 1. 목적 (Why) — 라이브에서 발화 중인 버그

`CandidateDiscoveryService.addNewCandidates`:

```java
for (StockNews n : newsMapper.findRecentEvents(...)) {
    for (String target : n.getTargets().split(",")) {
        String symbol = target.trim();
        if (symbol.isEmpty() || !seen.add(symbol)) continue;   // ← 여기서 symbol 소비
        ...
        if (!v.pass()) continue;                               // ← 자격 판정은 그 뒤
```

`seen.add(symbol)` 이 **자격 판정 전**에 symbol 을 소비한다. 따라서 한 종목의
**비자격 기사가 먼저 처리되면 그 종목은 그 회차에서 완전히 버려진다** — 같은 창에
자격 통과 기사가 있어도 등록되지 않는다.

게다가 `findRecentEvents` 에 **`ORDER BY` 가 없어** 처리 순서가 비결정적이다. 같은
데이터로 돌려도 회차마다 결과가 달라질 수 있다.

### 1.1 라이브 실증 (2026-10-08 21:50)

최근 24시간 창에서 "통과 기사와 탈락 기사를 동시에 가진" 종목:

| symbol | 기사수 | 통과 | 탈락 |
|--------|--------|------|------|
| `TSM` | 4 | **2** | 2 |

그리고 활성 후보는 **0건**이다. TSM 은 자격 통과 기사가 2건인데도 등록되지 않았다 —
탈락 기사(`TSMC September sales hit another record`,
`TSMC, 칩 수요 힘입어 3분기 매출 50% 급증` — 둘 다 `materialAmount=false`)가 먼저
처리된 것이다.

표본이 저녁 뉴스 351건이라 1종목이지만, 하루 870건 규모에서는 인기 종목마다 기사가
여러 건 붙으므로 훨씬 흔하다. **게이트를 조일수록(#865/#869/#877) 탈락 기사 비율이
올라가 이 버그의 영향도 커진다** — 오늘 조인 것이 이 버그를 증폭시켰다.

## 2. 설계 (What)

**기사마다 달라지는 조건은 symbol 을 소비하지 않게** 한다. 회차 내에서 불변인 조건만
소비한다.

| 조건 | 기사별로 달라지나 | `seen` 소비 |
|------|-------------------|-------------|
| `marketOf == null` (섹터명·MARKET) | 아니오 | **소비** |
| `existsActive` | 아니오 | **소비** |
| `level < 4` | **예** | 소비 안 함 |
| 촉매 자격 미달 | **예** | 소비 안 함 |
| 등록 성공 | — | **소비** |

### 2.1 평가 순서

```
1. seen.contains(symbol)            → 이미 결정된 종목이면 스킵
2. marketOf(symbol) == null         → 섹터명 등. seen 소비 후 스킵
3. existsActive(symbol)             → 이미 활성. seen 소비 후 스킵
4. level < 4                        → 이 기사로는 부족. seen 소비 안 함
5. !qualify(facts, title).pass()    → 이 기사로는 부족. seen 소비 안 함
6. insert + seen.add(symbol)
```

`marketOf` 를 자격 판정 **앞**으로 옮긴 것도 의도적이다 — 섹터명(반도체·바이오)은
구조적으로 종목이 아니므로 facts JSON 파싱을 돌릴 이유가 없고, 섹터명은 기사마다
반복 등장하므로 한 번만 평가해야 한다.

### 2.2 정렬

`findRecentEvents` 에 `ORDER BY COALESCE(published_at, fetched_at) DESC` 를 추가한다.

- **최신 기사가 먼저** 평가돼야 한다 — 가장 신선한 촉매가 등록 근거가 되는 것이 맞고,
  노트에 남는 제목도 최신이어야 사후 분석에 쓸모가 있다.
- 순서가 **결정적**이어야 같은 데이터에서 같은 결과가 나온다(재현 가능성).
- `active`·`activeSentiments`·`forSymbolBetween` 은 이미 같은 식으로 정렬돼 있어
  일관된다.

### 2.3 비용

종목당 자격 판정이 기사 수만큼 돌 수 있다. `qualify` 는 순수 함수(JSON 파싱 + 정규식)
이고 창이 24시간으로 제한돼 있어 부담이 없다. 반대로 `existsActive`(DB 조회)는 §2.1
순서상 종목당 최대 1회만 돈다 — 기존보다 호출이 늘지 않는다.

## 3. 함수 등재 (Function Registry)

| 함수 | 파일 | 변경 | I/O |
|------|------|------|-----|
| `CandidateDiscoveryService.addNewCandidates()` | 기존 수정 | §2.1 순서로 재배치 | I/O |
| `StockNewsMapper.findRecentEvents` | `mapper/StockNewsMapper.xml` | `ORDER BY` 추가 | SQL |

신규 함수 없음.

## 4. 인수조건 (Acceptance)

1. 같은 종목에 탈락 기사와 통과 기사가 섞여 있으면 **통과 기사로 등록**된다.
2. **탈락 기사가 먼저 와도** 등록된다(순서 무관).
3. 섹터명 타겟은 한 번만 평가되고 **자격 판정을 돌리지 않는다**.
4. 이미 활성인 후보는 중복 등록되지 않는다.
5. 한 회차에서 같은 종목이 두 번 등록되지 않는다.
6. `findRecentEvents` 가 최신순으로 정렬된다.
7. 배포 후 `TSM` 이 실제로 후보 등록된다.

## 5. 테스트 계획

`CandidateDiscoveryServiceTest`:
- 탈락 기사 → 통과 기사 순서로 주어질 때 등록된다 (**인수조건 1·2 — 이 버그의 핵심**)
- 통과 기사 → 탈락 기사 순서여도 한 번만 등록된다 (인수조건 5)
- 섹터명만 있는 기사 여러 건 → `insert` 미호출, 자격 판정 호출 없음(간접: 기사에
  facts 를 넣지 않아도 예외 없이 통과)
- 이미 활성인 종목 → `insert` 미호출, `existsActive` 1회만 호출 (인수조건 4)
- 같은 종목 통과 기사 2건 → `insert` 1회 (인수조건 5)

## 6. 리스크 / 되돌리기

| 리스크 | 완화 |
|--------|------|
| 등록 수가 갑자기 늘어 슬롯이 소진됨 | 슬롯은 `max-symbols=5` 로 고정이고 매수는 2단 종목 게이트를 또 통과해야 한다. 후보 풀이 늘어도 매수가 늘지는 않는다 |
| 종목당 자격 판정 반복으로 느려짐 | §2.3 — 순수 함수, 24시간 창. `existsActive` 호출은 오히려 종목당 1회로 고정 |

되돌리기: `seen.contains` → `!seen.add` 로 환원(1줄), `ORDER BY` 제거.

## 7. 범위 밖

- 같은 종목의 여러 통과 기사 중 "가장 좋은" 것을 고르는 로직(촉매점수 최대 등) —
  현재는 최신 1건을 쓴다. 점수 기반 선택은 별도 이슈로 가치가 입증돼야 한다.
