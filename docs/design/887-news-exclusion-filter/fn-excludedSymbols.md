# 함수 설계서: `NewsRiskExclusion.excludedSymbols` (#887)

> **부모 설계서**: ./README.md · **상태**: Draft
> **작성**: [AI] Architect · **구현**: `autotrade/NewsRiskExclusion.java:excludedSymbols` · **테스트**: `NewsRiskExclusionTest`

## 1. 시그니처

```java
public Set<String> excludedSymbols(LocalDateTime since)
```

## 2. 책임 (단일 책임, 1줄)

`since` 이후 리스크 이벤트 기사가 나온 종목 집합을 낸다 — **뉴스가 진입 경로에서 갖는 유일한 역할**.

## 3. 입력

| 파라미터 | 타입 | 제약/검증 | 설명 |
|----------|------|-----------|------|
| `since` | `LocalDateTime` | null → `IllegalArgumentException` | 조회 하한(KST). 호출부가 `now − risk-exclusion-days` 로 넘긴다 |

## 4. 출력

- **반환**: 종목코드 집합. 리스크 기사가 없으면 **빈 집합**(null 아님).
- **부수효과**: `stock_news` 조회(읽기 전용). 로깅.

## 5. 동작 / 알고리즘

```
1) news = newsMapper.riskCandidatesSince(since)
     — targets 가 비어있지 않은 기사만, since 이후, (symbol, title, facts) 투영
2) for each 기사:
     flag = RiskEventDetector.judge(title, facts)     // 기존 판정 재사용
     if (flag != null) → targets 의 모든 종목코드를 집합에 추가
3) return 집합
```

**`RiskEventDetector.judge` 를 재사용한다** — 매수 배제와 보유 매도가 같은 리스크 정의를 쓰게
해서 둘이 어긋나지 않도록 한다. `judge` 는 `facts.riskFlag`(NONE 아님) OR
`TitleGuard.announcedRiskOf(title)`(DILUTION 만) 이다.

### 매수 배제와 매도 트리거의 비대칭 — 의도된 것

#877 에서 제목 기반 LOSS·DELISTING 은 **매도** 트리거에서 빼냈다(오탐 시 실현손실).
그러나 **매수 배제**는 오탐의 비용이 기회비용뿐이다. 그래서 여기서는 `judge` 보다 넓게
`TitleGuard.riskFlagOf(title)`(DELISTING/GOVERNANCE/IMPAIRMENT/DILUTION/LOSS 전부)도 OR 로 쓴다.

```
flag = RiskEventDetector.judge(title, facts)          // 매도와 동일(좁음)
     ?? TitleGuard.riskFlagOf(title)                  // 매수 배제 전용(넓음)
```

> 근거: `CatalystQualifier` 가 이미 매수 경로에서 `riskFlagOf` 를 쓰고 있었다
> (`CatalystQualifier.qualify` 의 `titleRisk` 분기). 그 보호 범위를 잃지 않기 위함이다.

## 6. 에러 & 실패 모드

| 조건 | 처리 | 반환/예외 |
|------|------|-----------|
| `since == null` | 즉시 실패 | `IllegalArgumentException` |
| SQL 예외 | **예외 전파** | 호출부(`refresh`)가 사이클을 포기한다 |
| `targets` 가 null/빈 문자열 | 그 기사 무시 | — |
| `facts` 가 깨진 JSON | `NewsFacts.parse` 가 null 반환 → 제목 가드만 적용 | — |
| `title` 이 null | `judge`/`riskFlagOf` 가 null 반환 → 리스크 아님 | — |

**fail-closed**: 조회가 실패하면 배제 목록을 모르는 상태다. 그 상태로 후보를 등록하면
유상증자 공시가 난 종목을 살 수 있다. 그래서 예외를 삼키지 않고 전파해 **사이클 전체를
포기**한다(기존 후보는 그대로 유지되므로 매도 감시는 계속 돈다).

## 7. 엣지케이스

- **`targets` 에 여러 종목** (예: `005930,000660`) — 전부 배제한다. 교차오염 오탐
  가능성이 있지만(#877 이 매도에서 지적한 문제) 매수 배제는 보수적으로 간다.
- **US 종목코드** — `targets` 에 `TSM` 같은 티커도 온다. 집합에 그대로 넣는다
  (스크리너가 KR 만 보더라도 US 확장 시 바로 유효해진다).
- **같은 종목에 리스크 기사 여러 건** — `Set` 이므로 중복 없음.
- **리스크 기사가 `since` 직전에 있었던 경우** — 배제되지 않는다. `risk-exclusion-days`
  기본 7일은 `RiskEventDetector.LOOKBACK_HOURS`(24h)보다 길게 잡은 값이다.
- **기사가 많을 때의 메모리** — 투영 컬럼이 3개이고 7일 창이므로 수천 건 수준.

## 8. 복잡도 / 성능

- 시간 O(m), m = 7일 내 `targets` 있는 기사 수(실측 일 300~600건 → 7일 약 2~4천).
- 호출 빈도: 발굴 사이클 15분 1회. **틱 루프 안이 아니다.**
- 쿼리: `stock_news` 에 `fetched_at` 기반 조건 1개 + `targets IS NOT NULL`.
  `targets LIKE` 스캔이 아니라 범위 조회이므로 `RiskEventDetector.detect`(종목별 LIKE)보다
  가볍다.
