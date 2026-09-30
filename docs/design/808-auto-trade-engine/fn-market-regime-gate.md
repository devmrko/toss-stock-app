# 함수 설계서: `MarketRegimeGate.evaluate` (#808)

> **부모 설계서**: ./README.md · **상태**: Approved
> **작성**: [AI] Architect · **구현**: `com.cloudhandson.tossstock.autotrade.MarketRegimeGate#evaluate` · **테스트**: `MarketRegimeGateTest`
> **갱신(2026-10-01)**: MARKET S1(강한악재) 뉴스 카운트 조건 제거. 아래는 breadth 단일 조건으로 단순화된 현재 버전.

## 1. 시그니처
```java
boolean evaluate(int breadthPct, AutoTradeProperties.Gate props)
// true = 신규 매수 허용, false = 차단(기존 포지션 매도는 이 함수와 무관하게 항상 동작)
```

## 2. 책임 (단일 책임, 1줄)
시장 폭(breadth)을 근거로 "지금 신규 매수를 열어도 되는 시장 상황인가"를 판정한다.

## 3. 입력
| 파라미터 | 타입 | 제약/검증 | 설명 |
|----------|------|-----------|------|
| `breadthPct` | int | 0~100 | 전체 시장 상승비율(`/api/market/overview`와 동일한 `dailyMapper.breadth(100)` 계산) |
| `props` | AutoTradeProperties.Gate | - | `minBreadthPct` 임계값(아래 §9) — 하드코딩 금지, `application.yml`에서 조정 |

## 4. 출력
- **반환**: `boolean` — `breadthPct >= props.minBreadthPct()`.
- **부수효과**: 없음 — **순수 함수**(오버뷰 조회는 호출측 책임).

## 5. 동작 / 알고리즘
1. `breadthPct >= props.minBreadthPct()`(기본값 20)이면 `true`, 아니면 `false`. 그게 전부.

## 6. 에러 & 실패 모드
| 조건 | 처리 | 반환/예외 |
|------|------|-----------|
| `breadthPct`가 이상치(음수/100 초과) | 방어적 체크 | `IllegalArgumentException`(조용히 삼키지 않음) |

## 7. 엣지케이스
- breadthPct 정확히 임계값과 같을 때: `>=`로 통과(경계 포함 허용 쪽).

## 8. 복잡도 / 성능
- O(1). 스케줄러 틱마다 1회 — 성능 이슈 없음.

## 9. 의존성
- `application.yml`의 `auto-trade.gate.min-breadth-pct`(기본 20) — 사용자가 "운영하면서 조정하자"고 명시했으므로 재배포 없이 값만 바꿀 수 있어야 함.
- 호출측(`AutoTradeScheduler.marketGateOpen`)이 `dailyMapper.breadth(100)`로 입력을 만들어 전달.

## 10. 테스트 케이스
- [x] 정상: breadth=50 → `true`
- [x] 차단: breadth=28 → `false`
- [x] 경계: breadth == minBreadthPct 정확히 일치 → `true`
- [x] 실패: breadthPct = -5 또는 150 → `IllegalArgumentException`

## 11. 추적성
- 인수조건: #808 "시장상황 게이트" 요구사항(사용자 명시 요청: "뉴스에 따라 시장 상황을 반영").
- **2026-09-30**: `min-breadth-pct` 35→20 완화. 실측(최근 25거래일 breadth: 16.3~69.9%, 중앙값≈45%) — 35 기준은 통상적 약세일(25일 중 5일)까지 막고 있었음. 상세 근거는 README.md §11.
- **2026-10-01**: S1 뉴스 카운트 조건(원래 §5의 "뉴스 조건") 완전 제거. 계기: 9/30 밤 미장 세션에서 S1 6건이 전부 "코스피 3거래일 연속 하락", "외국인 팔자 행렬"처럼 **이미 일어난 가격하락을 사후 보도하는 후행(lagging) 뉴스**였음. 사용자 지적 — 후행 지표를 매수 차단의 필수(mandatory) 조건으로 쓰는 건 논리적으로 맞지 않고, breadth가 이미 그 가격하락을 수치로 반영하므로 중복이었음. `AutoTradeProperties.Gate`에서 `maxS1Count`/`lookbackDays` 필드 제거, `AutoTradeScheduler.marketGateOpen`에서 MARKET 뉴스 조회 로직 제거.
- 이 변경으로 시장 전체가 진짜 급락 중일 때의 방어는 전적으로 breadth 수치 자체(및 서킷브레이커·종목별 손절)에 의존하게 됨 — README.md §11 참고.
