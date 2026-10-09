# 함수 설계서: `UniverseScreener.screen` (#887)

> **부모 설계서**: ./README.md · **상태**: Draft
> **작성**: [AI] Architect · **구현**: `autotrade/UniverseScreener.java:screen` · **테스트**: `UniverseScreenerTest`

## 1. 시그니처

```java
public static ScreenResult screen(List<ScreeningRow> rows,
                                  Set<String> excluded,
                                  Map<String, Double> indexReturnByMarket,
                                  ScreenParams params)
```

```java
public record ScreenParams(Set<String> markets,            // 활성 시장 (예: {"KR"})
                           BigDecimal minTurnoverKr,       // KR 유동성 하한(원)
                           BigDecimal minTurnoverUs,       // US 유동성 하한(USD)
                           double maxExtensionPct,         // 급등률 상한(%)
                           int topN) {}

public record ScreenResult(List<ScreenCandidate> selected, ScreenFunnel funnel) {}
```

## 2. 책임 (단일 책임, 1줄)

로컬 집계 스냅샷을 원칙 §3 의 자동화 가능 조건으로 걸러 **상위 N 후보와 단계별 퍼널**을 낸다.

## 3. 입력

| 파라미터 | 타입 | 제약/검증 | 설명 |
|----------|------|-----------|------|
| `rows` | `List<ScreeningRow>` | null → 빈 리스트로 취급 | 종목별 60일 집계 |
| `excluded` | `Set<String>` | null → 빈 집합으로 취급 | 리스크 기사 보유 종목 |
| `indexReturnByMarket` | `Map<String,Double>` | 값 null 허용 | 시장별 지수 20일 수익률(%) |
| `params` | `ScreenParams` | **null → `IllegalArgumentException`** | 설정 스냅샷 |

> `params` 만 예외를 던진다 — 설정 누락은 프로그래밍 오류이고, 조용히 기본값으로
> 매수 경로를 열면 안 된다.

## 4. 출력

- **반환**: `ScreenResult` — `selected`(≤ `topN`, 거래대금 내림차순), `funnel`(7개 카운터).
- **부수효과**: 없음 — **순수 함수**. 로깅도 하지 않는다(호출부가 퍼널을 찍는다).

## 5. 동작 / 알고리즘

```
funnel.total = rows.size()

1) 시장 필터 + 데이터 충분
   - row.market 이 params.markets 에 없으면 폐기 (퍼널에 세지 않음 — 범위 외)
   - symbol 공백 / latestClose <= 0 / bars < 21  → 폐기
   funnel.afterBars = 잔존 수

2) 유동성
   - 하한 = market=="US" ? minTurnoverUs : minTurnoverKr
   - avgTurnover20 (null → 0) >= 하한
   funnel.afterLiquidity = 잔존 수

3) 상대강세 (fail-closed)
   - idx = indexReturnByMarket.get(market);  idx == null → 탈락
   - excess = excessReturnPctOf(row, idx);   excess == null → 탈락
   - excess > 0
   funnel.afterRelativeStrength = 잔존 수

4) 급등률 상한 (fail-closed)
   - ext = extensionPctOf(row);  ext == null → 탈락
   - ext < maxExtensionPct
   funnel.afterExtension = 잔존 수

5) 리스크 배제
   - !excluded.contains(symbol)
   funnel.afterRiskExclusion = 잔존 수

6) 정렬 + 절단
   - 정렬: avgTurnover20 내림차순, 동률이면 symbol 오름차순 (결정론적)
   - 앞에서 topN 개
   funnel.selected = 결과 크기
```

단계 순서는 **싼 것 먼저**다 — 1·2 는 산술 비교, 5 는 해시 조회다. 모든 단계가 O(1) 이므로
전체는 O(n log n)(정렬 지배).

## 6. 에러 & 실패 모드

| 조건 | 처리 | 반환/예외 |
|------|------|-----------|
| `params == null` | 즉시 실패 | `IllegalArgumentException` |
| `rows == null` 또는 빈 | 정상 처리 | 빈 `selected` + 전부 0 인 퍼널 |
| `excluded == null` | 빈 집합으로 간주 | — |
| `indexReturnByMarket` 에 해당 시장 없음 | 그 시장 전부 4단계 탈락 | 빈 결과(해당 시장분) |
| `low5 <= 0` / null | 4단계 탈락(fail-closed) | — |
| `closeBefore20 <= 0` / null | 3단계 탈락(fail-closed) | — |
| `avgTurnover20 == null` | 0 으로 간주 → 2단계 탈락 | — |
| `topN <= 0` | 빈 `selected`(킬스위치로 동작) | — |

**fail-closed 원칙**: 판정에 필요한 데이터가 없으면 **통과시키지 않는다**. 측정에서
급등 구간 하드손절률이 최대 89.9% 였으므로 "모르면 사지 않는다"가 기대값상 유리하다.

## 7. 엣지케이스

- **지수 수익률이 음수**(실측 2026-10-08: −5%) — `excess > 0` 은 "지수보다 낫다"이므로
  하락장에서는 덜 빠진 종목도 통과한다. 의도된 동작이다(상대강세의 정의).
  절대 수익률 하한을 추가로 걸지 **않는다** — 측정 근거가 없다.
- **동일 거래대금 다수** — `symbol` 2차 정렬로 결정론적. 테스트가 이걸 고정한다.
- **topN > 통과 수** — 있는 만큼 반환.
- **중복 종목 행** — 스냅샷 SQL 이 `GROUP BY symbol` 이므로 발생하지 않는다. 방어적으로
  중복이 와도 둘 다 후보가 되지만 `existsActive` 가 호출부에서 막는다.
- **동시성** — 순수 함수이고 입력을 변형하지 않는다. 정렬은 새 리스트에 한다
  (`rows` 를 `sort()` 로 제자리 정렬하면 호출부의 리스트를 훼손한다 — 금지).

## 8. 복잡도 / 성능

- 시간 O(n log n), n ≈ 3,805. 공간 O(n).
- 호출 빈도: 발굴 사이클 15분 1회. 틱 루프 안이 **아니다**.
- 실측 단계별 잔존(2026-10-08 KR, 급등률 임계 3.0): 전체 3,472 → 유동성 **1,769** →
  상대강세 **1,243** → 급등률 **679** → 선정 **40**. (리스크배제 단계는 미측정 — 구현 후 관측)
- 단계 순서는 위 실측과 같다. AND 필터이므로 최종 수는 순서와 무관하지만, 퍼널 로그의
  중간값이 실측과 대조 가능해야 하므로 순서를 고정한다.
