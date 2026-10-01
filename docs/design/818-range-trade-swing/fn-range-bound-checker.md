# 함수 설계서: `RangeBoundChecker.evaluate` (#818)

> **부모 설계서**: ./README.md · **상태**: Draft
> **작성**: [AI] Architect · **구현**: `com.cloudhandson.tossstock.rangetrade.RangeBoundChecker`(TBD) · **테스트**: `RangeBoundCheckerTest`(TBD)

## 1. 시그니처
```java
Result evaluate(List<DailyOhlcv> window, RangeTradeProperties props)
// Result(boolean isRangeBound, BigDecimal low, BigDecimal high)
```

## 2. 책임 (단일 책임, 1줄)
주어진 일봉 구간이 "추세 없이 일정 밴드 안에서 반복 왕복하는 박스권"인지 판정하고, 맞다면 그 밴드의 저점/고점을 반환한다.

## 3. 입력
| 파라미터 | 타입 | 제약/검증 | 설명 |
|----------|------|-----------|------|
| `window` | List<DailyOhlcv> | `props.windowDays()`+1개 이상 필요(날짜순 무관, 내부 정렬) | 한 종목의 최근 N거래일 일봉 |
| `props` | RangeTradeProperties | - | `minWidthPct`, `maxWidthPct`, `maxTrendDriftPct` 등(§9) |

## 4. 출력
- **반환**: `Result(isRangeBound, low, high)`. `isRangeBound=false`면 `low`/`high`는 `null`.
- **부수효과**: 없음 — **순수 함수**.

## 5. 동작 / 알고리즘
1. 데이터 부족(윈도우 미만) → `isRangeBound=false`.
2. `low`/`high` = 윈도우 내 `low_p` 최솟값 / `high_p` 최댓값.
3. `widthPct = (high - low) / ((high + low) / 2) * 100`. `minWidthPct` 미만이거나 `maxWidthPct` 초과면 탈락(각각 "거래비용 대비 안 남음", "진짜 박스권이 아닐 가능성").
4. 추세 유무: 윈도우를 전반부/후반부로 반씩 나눠 각각의 종가 평균을 구하고, `|후반부평균 - 전반부평균| / 중간값 * 100`이 `maxTrendDriftPct` 초과면 탈락(지속적으로 한 방향으로 흘러간 것 — 박스권이 아니라 완만한 추세).
5. 위 둘 다 통과하면 `isRangeBound=true`, `(low, high)` 반환.

## 6. 에러 & 실패 모드
| 조건 | 처리 | 반환/예외 |
|------|------|-----------|
| `window`가 `null` 또는 길이 부족 | 데이터 부족 | `isRangeBound=false` (fail-closed) |
| `window` 내 `high_p`/`low_p`가 일부 `null` | 해당 레코드 제외하고 계산, 유효 레코드가 부족해지면 데이터 부족과 동일 처리 | `isRangeBound=false` |
| `props`가 `null` | 호출측 책임(방어 안 함) | `NullPointerException` 허용 |

## 7. 엣지케이스
- `low == high`(완전히 안 움직인 경우): `widthPct=0`, `minWidthPct` 조건에서 자연히 탈락.
- 윈도우 정확히 경계값(`props.windowDays()`+1개): 통과(최소 요구치 충족).
- 전반부/후반부 분할 시 윈도우가 홀수개면: 중간 1개는 전반부에 포함(구현 시 명시, 결과에 큰 영향 없음).
- 밴드 안에서 "왕복 횟수"(하단/상단 터치 횟수)는 v1에서 **체크하지 않음** — 폭+추세드리프트만으로 판정(단순화, §12 후속 과제로 터치 횟수 요건 추가 검토 가능).

## 8. 복잡도 / 성능
- O(N), N = windowDays(기본 60). 일 1회, 유동성 통과 종목 수만큼 호출 — 성능 이슈 없음(1분 틱이 아니므로 #808보다 여유 있음).

## 9. 의존성
- `application.yml`의 `range-trade.window-days`(60), `range-trade.min-width-pct`(**26.0** — 2026-10-01 Ellman ROO 방식으로 역산, README §12 계산식 참고. 왕복비용 0.23%는 #808 195870 실측, 목표수익 3%는 가정값), `range-trade.max-width-pct`(50.0), `range-trade.max-trend-drift-pct`(15.0) — **`min-width-pct` 외엔 전부 초기 추정치, 확정 아님**(README §12).

## 10. 테스트 케이스
- [ ] 정상: 인위적으로 생성한 왕복 패턴(예: 100↔120 반복) → `isRangeBound=true`, low=100/high=120 근사
- [ ] 실패: 지속 하락 종목(매일 전일보다 낮음) → `isRangeBound=false`(추세 드리프트 초과)
- [ ] 실패: 너무 좁은 밴드(폭 5% 미만) → `isRangeBound=false`
- [ ] 실패: 너무 넓은 밴드(폭 60% 초과) → `isRangeBound=false`
- [ ] 실패: 데이터 부족(windowDays 미만) → `isRangeBound=false`
- [ ] 경계: 폭/드리프트가 정확히 임계값과 일치 → 통과(`<=`/`>=` 방향 명확히)

## 11. 추적성
- 인수조건: #818 "추세가 뚜렷한 종목은 탈락한다".
- 관련 ADR: 없음.
