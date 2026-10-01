# 함수 설계서: `RangeTradeSignal.decide` (#818)

> **부모 설계서**: ./README.md · **상태**: Draft
> **작성**: [AI] Architect · **구현**: `com.cloudhandson.tossstock.rangetrade.RangeTradeSignal`(TBD) · **테스트**: `RangeTradeSignalTest`(TBD)

## 1. 시그니처
```java
Signal decide(BigDecimal current, BigDecimal rangeLowAtEntry, BigDecimal rangeHighAtEntry,
              boolean holding, RangeTradeProperties props)
// Signal: BUY | PROFIT_TAKE | RANGE_BREAKDOWN | NONE
```

## 2. 책임 (단일 책임, 1줄)
현재가와 (보유 중이면 진입 시점에 고정된) 밴드 하단/상단을 근거로 매수/익절/손절/유지 중 하나를 순수 판정한다.

## 3. 입력
| 파라미터 | 타입 | 제약/검증 | 설명 |
|----------|------|-----------|------|
| `current` | BigDecimal | > 0 | 현재가 |
| `rangeLowAtEntry` | BigDecimal | > 0 | 미보유 시 = 방금 `RangeBoundChecker`가 산출한 밴드 하단. 보유 시 = **진입 시점에 고정 저장된** 값(트레일링 아님, README §9) |
| `rangeHighAtEntry` | BigDecimal | > `rangeLowAtEntry` | 동일 — 밴드 상단 |
| `holding` | boolean | - | 이미 보유 중인지(true면 BUY 판정은 안 함, 매도만 검토) |
| `props` | RangeTradeProperties | - | `entryZonePct`, `exitZonePct`, `breakdownPct` |

## 4. 출력
- **반환**: `Signal` enum. **부수효과 없음(순수)**.
- `holding=false`일 때만 `BUY` 가능, `holding=true`일 때만 `PROFIT_TAKE`/`RANGE_BREAKDOWN` 가능. 조건에 안 맞으면 `NONE`.

## 5. 동작 / 알고리즘
**미보유(`holding=false`) — 매수 판정**:
1. `entryCeiling = rangeLowAtEntry * (1 + entryZonePct/100)`.
2. `current <= entryCeiling` 이면 `BUY`, 아니면 `NONE`.

**보유(`holding=true`) — 매도 판정**:
1. `profitFloor = rangeHighAtEntry * (1 - exitZonePct/100)`. `current >= profitFloor` → `PROFIT_TAKE`.
2. 아니면 `breakdownFloor = rangeLowAtEntry * (1 - breakdownPct/100)`. `current <= breakdownFloor` → `RANGE_BREAKDOWN`.
3. 둘 다 아니면 `NONE`(계속 보유).
4. **우선순위**: 이론상 둘 다 동시에 참이 될 수 없음(`profitFloor > rangeHighAtEntry > rangeLowAtEntry > breakdownFloor`이 항상 성립하도록 `props` 값 자체를 검증 — §7).

## 6. 에러 & 실패 모드
| 조건 | 처리 | 반환/예외 |
|------|------|-----------|
| `current <= 0` | 방어적 체크 | `IllegalArgumentException` |
| `rangeLowAtEntry >= rangeHighAtEntry` | 밴드 자체가 무효(상단이 하단보다 낮거나 같음) | `IllegalArgumentException` |
| `entryZonePct`/`breakdownPct` 설정이 겹쳐서 `entryCeiling > breakdownFloor` 역전 | 설계 결함 가능성 — 운영 전 설정값 검증 필요(단위테스트로 강제, §10) | 함수 자체는 막지 않음(설정 책임은 호출측/운영) |

## 7. 엣지케이스
- 경계값: `current`가 정확히 `entryCeiling`/`profitFloor`/`breakdownFloor`와 일치 — 전부 `<=`/`>=`로 포함(경계 포함 쪽, #808 `MarketRegimeGate`와 동일 관례).
- `entryZonePct`가 너무 크면(예: 50%) 밴드 중간까지 다 "매수구간"이 돼버림 — 함수는 막지 않지만 README §9에서 지적한 "진입구간과 손절선이 겹치면 안 됨" 문제와 연결, 파라미터 조합 검증은 운영 설정 책임.
- `holding=true`인데 `current`가 밴드 한가운데(두 조건 다 거짓) — 정상 `NONE`, 계속 보유.

## 8. 복잡도 / 성능
- O(1). 일 1회, 보유 포지션 수 + 신규 후보 수만큼 호출 — 성능 이슈 없음.

## 9. 의존성
- `application.yml`의 `range-trade.entry-zone-pct`(10.0), `range-trade.exit-zone-pct`(10.0), `range-trade.breakdown-pct`(5.0) — 전부 초기 추정치(README §12).

## 10. 테스트 케이스
- [ ] 정상: 미보유, `current`가 하단+10% 이내 → `BUY`
- [ ] 정상: 미보유, `current`가 밴드 중간 → `NONE`
- [ ] 정상: 보유, `current`가 상단-10% 이상 → `PROFIT_TAKE`
- [ ] 정상: 보유, `current`가 진입시점 하단보다 5% 이상 낮음 → `RANGE_BREAKDOWN`
- [ ] 경계: `current == entryCeiling` → `BUY`(포함), `current == breakdownFloor` → `RANGE_BREAKDOWN`(포함)
- [ ] 실패: `current <= 0` 또는 `rangeLowAtEntry >= rangeHighAtEntry` → 예외
- [ ] 설정 검증: 기본 파라미터(entry 10%/exit 10%/breakdown 5%)로 `entryCeiling < breakdownFloor`가 항상 성립하는지 — 안 그러면 진입 직후 바로 손절되는 모순 상황 발생 가능(README §9), 이 조합이 실제로 안전한지 수치로 확인하는 테스트 1건 추가.

## 11. 추적성
- 인수조건: #818 "밴드 하단 근처일 때만 매수", "상단 근처 익절 / 진입시점 하단 이탈 손절".
- 관련 ADR: 없음.
