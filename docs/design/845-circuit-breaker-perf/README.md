# 설계서: 서킷브레이커 평가손익 계산 O(n) 성능 개선 (#845)

> **상태**: Approved
> **작성**: [AI] Architect · **최종수정**: 2026-10-07
> **추적성** — Redmine: #845 · 관련 ADR: 없음
> · 구현 파일: `AutoTradePositionMapper.java`/`.xml`, `RangeTradePositionMapper.java`/`.xml`,
>   `AutoTradeScheduler.java`, `RangeTradeScheduler.java` ·
>   테스트: 기존 `AutoTradeSchedulerTest`/`RangeTradeSchedulerTest` 회귀 확인

## 1. 목적 (Why)
`checkCircuitBreaker`가 매 틱(#808 1분/#818 1일)마다 `positionMapper.findAll()`로
**전체 포지션 이력**(현재 54건, 거래가 쌓일수록 계속 증가 — 오늘 #835 사고 때 45회
왕복으로 한 번에 90건 쌓인 전례 있음)을 Java로 가져와 realized/unrealized를 재합산한다.
거래가 많아질수록 매 틱이 느려지는 구조.

## 2. 범위 (Scope)
- **포함**: EXITED 포지션의 realized 합산을 DB `SUM` 집계 쿼리 1개(스칼라 반환, 행
  수와 무관하게 빠름)로 대체. HOLDING 포지션은 기존처럼 개별 현재가 조회(이미 적은
  수로 제한됨 — #808 최대 5, #818 최대 2라 이 부분은 그대로 둬도 문제없음).
- **제외 (out of scope)**: 계산 결과(판정 로직) 자체는 전혀 안 바꾼다 — 순수 성능
  리팩터링.

## 3. 인수조건 (Acceptance Criteria)
- [ ] `checkCircuitBreaker`의 realized 합산이 `findAll()` 없이 `SUM` 쿼리 1개로 계산됨.
- [ ] EXITED 포지션이 0건이어도 NULL이 아니라 0을 반환(COALESCE).
- [ ] 계산 결과(트립 여부)가 기존 방식과 동일함(회귀 테스트로 확인).

## 4~9. (단순 변경이라 §5~9 생략 — 함수 명세로 충분)

## 7. 함수 명세

| 함수 | 책임 | 시그니처 | 복잡? |
|------|------|----------|-------|
| `AutoTradePositionMapper.realizedPnlTotal` | 전체 EXITED 포지션 realized 합계(스칼라) | `BigDecimal realizedPnlTotal()` | 단순 |
| `RangeTradePositionMapper.realizedPnlTotal` | 동일(레인지 트랙) | `BigDecimal realizedPnlTotal()` | 단순 |
| `AutoTradeScheduler.checkCircuitBreaker`(수정) | `findAll()` → `realizedPnlTotal()`+`findHolding()` | 시그니처 불변 | 단순 |
| `RangeTradeScheduler.checkCircuitBreaker`(수정) | 동일 | 시그니처 불변 | 단순 |

## 10. 테스트 계획
기존 `AutoTradeSchedulerTest`/`RangeTradeSchedulerTest`의 서킷브레이커 관련 테스트가
`findAll()` 모킹을 쓰던 걸 `realizedPnlTotal()` 모킹으로 바꿔 동일 결과를 내는지 확인.

## 11~12. 리스크/미해결질문
없음 — 계산 로직 불변, 성능만 개선.
