# 설계서: 수수료/세금 직접 계산(#841 정정) (#847)

> **상태**: Approved
> **작성**: [AI] Architect · **최종수정**: 2026-10-07
> **추적성** — Redmine: #847(정정 대상 #841) · 관련 ADR: 없음
> · 구현 파일: `TradingFeeCalculator.java`(신규, autotrade 패키지에 두고 양쪽에서 재사용),
>   `OrderExecutor.java`, `RangeOrderExecutor.java`(수정) ·
>   테스트: `TradingFeeCalculatorTest`(신규)

## 1. 목적 (Why)
#841은 `TossOrder.Execution.commission()/tax()`를 저장하도록 했으나, 실측(2026-10-07)
결과 이 필드는 `/api/v1/orders/{id}` 응답에서 **항상 "0"**(신규 주문~5일 전 이미 정산된
주문 13건, BUY/SELL 모두 포함해 확인). 실제 수수료/세금은 그 엔드포인트에 없고,
`/api/v1/holdings`의 `item.cost`와 `/api/v1/commissions`(요율표)에만 존재한다.

보유 5종목(086450/006400/053800/003670/066570) 교차검증 결과, 전부 소수 4자리까지
일치하는 요율을 확인:
- **세금**: 매도금액(filledAmount)의 **정확히 0.20%**(KR만, 매도에만 적용 — 한국
  증권거래세+농특세 통합 관행과 일치).
- **수수료**: `/api/v1/commissions` 응답 그대로 — KR 0.015%, US 0.1%(매수/매도 각각).

## 2. 범위 (Scope)
- **포함**: `#841`에서 추가한 `commission`/`tax` 컬럼은 그대로 유지. 값을 "주문응답에서
  읽기"(항상 0으로 확인됨) 대신 "요율로 직접 계산"으로 교체. `OrderExecutor`/
  `RangeOrderExecutor` 둘 다.
- **제외 (out of scope)**: 미국 세금(SEC fee 등 상당 항목) — 아직 실측 미확인. 0으로
  유지(과소추정 가능성을 주석으로 명시 — fail-closed 방향이 아니라 "모름=0"이라는 점을
  분명히 남김, §9).
- **제외**: `/api/v1/commissions` 실시간 조회 — 요율은 `startDate`/`endDate`가 있어
  이론상 바뀔 수 있으나(실측 현재값: KR `2021-01-01~9999-12-31`, US `~2026-10-08`),
  매 주문마다 호출하면 불필요한 API 호출 추가(레이트리밋 민감). 상수로 하드코딩하고,
  US 요율의 `endDate`가 가깝다는 점만 코드 주석으로 남겨 추후 재확인 유도(§11).

## 3. 인수조건 (Acceptance Criteria)
- [ ] KR 매수: `commission = filledAmount × 0.00015`(반올림), `tax = 0`.
- [ ] KR 매도: `commission = filledAmount × 0.00015`, `tax = filledAmount × 0.002`.
- [ ] US 매수/매도: `commission = filledAmount × 0.001`, `tax = 0`(미확인, §2).
- [ ] 드라이런 주문도 동일하게 계산해 저장(실제 비용 추정치를 보여주는 게 목적이라
      드라이런이라고 생략하지 않음 — 사용자가 "드라이런이었어도 실제로 샀으면 얼마
      들었을지" 알 수 있게).
- [ ] 기존 테스트 전부 통과 + 신규 `TradingFeeCalculatorTest`(5케이스: KR매수/KR매도/
      US매수/US매도/0원 경계).

## 4~6. 컨텍스트·아키텍처·데이터모델
변경 없음(컬럼은 #841에서 이미 추가됨) — 계산 로직만 교체.

## 7. 함수 명세

| 함수 | 책임 | 시그니처 | 복잡? |
|------|------|----------|-------|
| `TradingFeeCalculator.commission` | 체결금액×수수료율(매수/매도 공통) | `static BigDecimal commission(BigDecimal filledAmount, String market)` | 단순 |
| `TradingFeeCalculator.tax` | 매도시에만 체결금액×0.20%(KR), 그 외 0 | `static BigDecimal tax(BigDecimal filledAmount, String market, String side)` | 단순 |
| `OrderExecutor.buy/sell`(수정) | commission/tax를 주문응답 대신 계산값으로 저장 | 시그니처 불변 | 단순 |
| `RangeOrderExecutor.buy/sell`(수정) | 동일 | 시그니처 불변 | 단순 |

## 8. 흐름
`filledPrice × qty`(이미 계산되는 filledAmount)를 `TradingFeeCalculator`에 넘겨
commission/tax를 구하고, 기존 `feeOf(order, ...)` 호출을 이 값으로 교체한다.
주문 자체가 실패한 경우(success=false)는 체결이 없었으므로 commission/tax는 0(또는
null) — 기존 분기 구조상 성공 경로에서만 계산하면 자동으로 해당됨.

## 9. 엣지케이스
- US 세금 미확인 — 코드 주석에 명시, 0으로 저장(과소추정 가능성 있음을 숨기지 않음).
- `/api/v1/commissions`의 US 요율 `endDate: "2026-10-08"`(실측 시점 기준 내일) — 만료되면
  요율이 바뀔 수 있어 상수 값에 "확인 필요" 주석을 남김(§11, 자동 갱신은 범위 밖).

## 10. 테스트 계획
`TradingFeeCalculatorTest`: KR 매수(세금 0), KR 매도(세금 0.20%), US 매수/매도(세금 0),
0원 입력 경계.

## 11. 리스크 & 대안 검토
- 대안(기각): 매 주문마다 `/api/v1/commissions` 실시간 조회 — 레이트리밋 민감한
  경로(주문 직전)에 불필요한 호출 추가. 요율이 거의 안 바뀌는 값(KR은 2021년부터 고정)
  이라 상수화가 합리적, 되돌리기 쉬운 결정.
- 리스크: 이 값은 "계산된 추정치"이지 토스가 공식 확정한 금액이 아님 — 반올림/최소수수료
  등 미세한 차이가 있을 수 있음(그래도 기존 0보다는 훨씬 정확). 주석으로 "추정치"임을
  명시.

## 12. 미해결 질문
- 미국 거래세(SEC fee 등) 요율 — 후속 실측 필요.
