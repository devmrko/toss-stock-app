# 함수 설계서: `TrailingStopCalculator.decide` (#808)

> **부모 설계서**: ./README.md · **상태**: Approved
> **작성**: [AI] Architect · **구현**: `com.cloudhandson.tossstock.autotrade.TrailingStopCalculator#decide` (TBD) · **테스트**: `TrailingStopCalculatorTest`(TBD)

## 1. 시그니처
```java
ExitReason decide(BigDecimal current, BigDecimal peak, BigDecimal avgCost, double hardStopPct, double trailPct)
// ExitReason enum { NONE, HARD_STOP, TRAIL_STOP }
```

## 2. 책임 (단일 책임, 1줄)
현재가·고점·평단가로 하드손절/추적손절 매도 여부를 판정한다(원칙 §4, 대화 중 시뮬레이션으로 검증한 "고정% 방식").

## 3. 입력
| 파라미터 | 타입 | 제약/검증 | 설명 |
|----------|------|-----------|------|
| `current` | BigDecimal | > 0 | 현재가 |
| `peak` | BigDecimal | ≥ avgCost, > 0 | 진입 이후 고점(호출측이 매 틱 갱신해서 전달) |
| `avgCost` | BigDecimal | > 0 | 평단가(진입가) |
| `hardStopPct` | double | 0 < x < 100 | 하드손절 %(기본 10) |
| `trailPct` | double | 0 < x < 100 | 추적손절 %(기본 10, 멀티배거는 15~20 — 종목별로 다르게 줄 수 있게 파라미터화) |

## 4. 출력
- **반환**: `ExitReason` — `HARD_STOP`(평단가 기준 -hardStopPct% 이하), `TRAIL_STOP`(고점 기준 -trailPct% 이하, 단 하드손절 우선), `NONE`(매도 안 함).
- **부수효과**: 없음 — **순수 함수**. (시뮬레이션 스크립트의 `simulate_fixed`와 동일 로직을 프로덕션 코드로 옮긴 것 — 대화 중 검증된 공식 그대로.)

## 5. 동작 / 알고리즘
1. `hardFloor = avgCost * (1 - hardStopPct/100)`
2. `trailFloor = peak * (1 - trailPct/100)`
3. `stopPrice = max(hardFloor, trailFloor)`
4. `current <= stopPrice` 이면:
   - `current <= hardFloor` 이고 `hardFloor >= trailFloor` 이면 `HARD_STOP`
   - 아니면 `TRAIL_STOP`
5. 아니면 `NONE`

> 대화 중 시뮬레이션(`simulate_trailing_stop.py`)의 `stop_price = max(entry*(1-hard), peak*(1-trail))` 공식과 동일 — 백테스트에 쓴 것과 프로덕션 로직이 어긋나지 않도록 그대로 이식.

## 6. 에러 & 실패 모드
| 조건 | 처리 | 반환/예외 |
|------|------|-----------|
| `current`/`avgCost` ≤ 0 | 호출측 데이터 오류 | `IllegalArgumentException` |
| `peak < avgCost` | 비정상 상태(고점이 진입가보다 낮음 — 호출측이 peak 갱신을 안 했거나 버그) | `IllegalArgumentException`(조용히 삼키지 않음 — CLAUDE.md 원칙) |

## 7. 엣지케이스
- `peak == avgCost`(아직 한 번도 신고점 안 찍음): `trailFloor == hardFloor` → 사실상 하드손절만 작동. 정상 동작.
- `current == stopPrice` 정확히 일치: 매도 트리거(`<=`, 시뮬레이션과 동일하게 경계 포함).
- `hardStopPct == trailPct`: 항상 `HARD_STOP`으로 분류(우선순위 규칙 그대로).

## 8. 복잡도 / 성능
- O(1). 스케줄러 틱마다 보유 5종목 이하 호출 — 성능 이슈 없음.

## 9. 의존성
- 없음(순수 함수, 외부 상태 참조 안 함). `peak` 갱신은 호출측(`PositionMonitor`)이 매 틱 `max(기존peak, current)`로 별도 관리 후 전달.

## 10. 테스트 케이스
- [ ] 정상: avgCost=100, peak=150, trail=10 → stopPrice=135, current=130 → `TRAIL_STOP`
- [ ] 정상: avgCost=100, peak=100(신고점 없음), current=89 → `HARD_STOP`
- [ ] 경계: current == stopPrice 정확히 일치 → 매도(NONE 아님)
- [ ] 경계: peak == avgCost일 때 hardFloor/trailFloor 관계
- [ ] 실패: peak < avgCost → `IllegalArgumentException`
- [ ] 실패: current ≤ 0 → `IllegalArgumentException`

## 11. 추적성
- 인수조건: #808 "단위테스트 통과(경계값 포함)".
- 관련 ADR: 없음(기존 원칙 §4의 코드화).
