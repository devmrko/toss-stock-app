# 함수 설계서: `ReentryStageCalculator.decide` (재진입 알림, 이슈 없음)

> **부모 설계서**: ./README.md · **상태**: Approved
> **작성**: [AI] Architect · **구현**: `reentry/ReentryStageCalculator.java:decide` · **테스트**: `reentry/ReentryStageCalculatorTest.java`

## 1. 시그니처
```java
static ReentryStage decide(BigDecimal current, BigDecimal reference,
                            double stage1Pct, double stage2Pct,
                            boolean stage1Done, boolean stage2Done)
```

## 2. 책임 (단일 책임, 1줄)
현재가·기준가·임계값·기발송 이력으로부터 "이번 실행에서 새로 보낼 알림 단계"를 결정한다.

## 3. 입력
| 파라미터 | 타입 | 제약/검증 | 설명 |
|----------|------|-----------|------|
| `current` | BigDecimal | null 불가 | 조회된 현재가 |
| `reference` | BigDecimal | null 불가, `> 0` (0 이하면 예외) | 정리(매도) 시점 기준가 |
| `stage1Pct` | double | `0 < stage1Pct < stage2Pct` 가정(호출측 보장) | 1단계 상승률 임계값(%) |
| `stage2Pct` | double | 위와 동일 | 2단계 상승률 임계값(%) |
| `stage1Done` | boolean | - | 1단계 알림을 이미 보냈는가 |
| `stage2Done` | boolean | - | 2단계 알림을 이미 보냈는가 |

## 4. 출력
- **반환**: `ReentryStage` — `NONE` / `STAGE1` / `STAGE2`.
- **부수효과**: 없음(순수 함수).

## 5. 동작 / 알고리즘
1. `reference <= 0`이면 `IllegalArgumentException`.
2. `pct = (current - reference) / reference * 100` 계산.
3. `pct >= stage2Pct && !stage2Done` → `STAGE2` 반환(우선순위 최상 — 이미 2단계를 건너뛰고 급등한 경우도 커버).
4. 그 외 `pct >= stage1Pct && !stage1Done` → `STAGE1` 반환.
5. 그 외 → `NONE`.

## 6. 에러 & 실패 모드
| 조건 | 처리 | 반환/예외 |
|------|------|-----------|
| `reference <= 0` | 즉시 실패 | `IllegalArgumentException` |
| `current == null` / `reference == null` | 즉시 실패 | `NullPointerException`(호출측이 null 넘기지 않도록 보장) |

## 7. 엣지케이스
- `pct`가 정확히 임계값과 같음(경계) → 임계값 **이상**이면 해당 단계 트리거(`>=`).
- 이미 `stage2Done=true`인데 다시 호출됨 → `NONE`(재알림 금지).
- `stage1Done=true, stage2Done=false`인 상태에서 급등해 `pct >= stage2Pct` → `STAGE2` 반환(1단계를 다시 보내지 않고 2단계로 승격).

## 8. 복잡도 / 성능
- O(1), 스케줄러 루프(활성 행 수만큼) 내부에서 매분 호출되지만 순수 산술 연산이라 비용 무시 가능.

## 9. 의존성
- 없음(외부 호출·설정 없음). `ReentryAlertService`가 이 함수를 호출.

## 10. 테스트 케이스
- [ ] 정상: `pct < 5% → NONE`
- [ ] 경계: `pct == 5.0% → STAGE1`, `pct == 10.0% → STAGE2`
- [ ] 이미 보낸 단계: `stage1Done=true, pct=7% → NONE`(1단계 중복 금지)
- [ ] 단계 승격: `stage1Done=true, stage2Done=false, pct=12% → STAGE2`
- [ ] 실패: `reference=0 → IllegalArgumentException`

## 11. 추적성
- 인수조건: README.md §3 "상승률 +5%/+10% 임계값·재알림 방지".
- 관련 ADR: 없음.
