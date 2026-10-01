# 함수 설계서: `RangeBoundChecker.evaluate` (#818)

> **부모 설계서**: ./README.md · **상태**: Approved
> **작성**: [AI] Architect · **구현**: `com.cloudhandson.tossstock.rangetrade.RangeBoundChecker` · **테스트**: `RangeBoundCheckerTest`(12건)
> **2026-10-01 Developer 보완**: §10 첫 케이스의 예시 밴드가 §9의 `min-width-pct` 상향(15→26)과 어긋나 있어 정정(아래 참고).

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
| `close_p` 또는 `trade_date`가 `null` | 2026-10-01 Developer 보완 — 종가는 추세 드리프트(전반부/후반부 평균) 계산에, 거래일은 내부 정렬에 반드시 필요하므로 **같이 제외 대상**에 포함 | `isRangeBound=false` |
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
- [x] 정상: 인위적으로 생성한 왕복 패턴 → `isRangeBound=true`, 정확한 밴드값. **2026-10-01 Developer 정정**: 원래 예시(100↔120)는 폭이 18.2%(중간값 기준)라 `min-width-pct`가 15→26으로 상향된 뒤엔 오히려 **탈락**해야 하는 값이다(§9 변경 시 이 예시가 함께 갱신되지 않았음). 테스트는 100↔130(26.09%)으로 통과 케이스를, 100↔120으로 "왕복비용/목표수익 미달 탈락" 케이스를 각각 검증한다.
- [x] 실패: 지속 하락 종목(매일 전일보다 낮음) → `isRangeBound=false`(추세 드리프트 초과)
- [x] 실패: 너무 좁은 밴드(폭 5% 미만, 테스트는 100↔102=2.0%) → `isRangeBound=false`
- [x] 실패: 너무 넓은 밴드(폭 60% 초과, 테스트는 100↔200=66.7%) → `isRangeBound=false`
- [x] 실패: 데이터 부족(windowDays+1 미만, `null`/빈 리스트/고가·저가·종가 `null` 섞임 포함) → `isRangeBound=false`
- [x] 경계: 폭/드리프트가 정확히 임계값과 일치 → 통과(테스트: low=87/high=113 → 폭 정확히 26.0%, 전반부 종가 92.5·후반부 107.5 → 드리프트 정확히 15.0%)
- [x] 추가(2026-10-01 Developer): 입력 순서 무관(내부 정렬), 입력 리스트 비변형(순수성), 홀수 윈도우에서 중간 1개가 전반부에 포함되는 규칙(§7), 완전 횡보(폭 0%) 탈락

## 11. 추적성
- 인수조건: #818 "추세가 뚜렷한 종목은 탈락한다".
- 관련 ADR: 없음.
