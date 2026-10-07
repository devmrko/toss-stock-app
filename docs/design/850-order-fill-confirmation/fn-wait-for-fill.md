# 함수 설계서: waitForFill (#850)

## 시그니처
```java
private TossOrder waitForFill(TossOrder initial)
```
(`OrderExecutor`, `RangeOrderExecutor` 양쪽에 동일하게 — private, 상태 없음 외엔 공유 안 함,
§11 기존 "#808 클래스를 건드리지 않기 위해 공유하지 않는다" 원칙 유지.)

## 책임
`placeOrder()`가 돌려준 주문이 아직 진행 중(미확정) 상태면, 짧게 몇 번 재조회해서 확정된
상태(또는 재시도 소진)의 주문 정보를 반환한다. 이미 확정 상태면 재조회 없이 즉시 반환.

## 입력
- `initial`: `placeOrder()`가 반환한 `TossOrder`(null 가능 — 호출부에서 이미 null 처리 중이므로
  이 함수도 null-safe해야 함).

## 출력
- `TossOrder` — 확정됐거나(가장 바람직), 재시도 소진 후 마지막으로 얻은 값(차선), 또는 `initial`
  그대로(재조회 불필요했거나 전부 실패).

## 상수
- `MAX_FILL_POLL_ATTEMPTS = 4`
- `FILL_POLL_DELAY_MS = 300`
(최악 지연 1.2초/주문 — §4 레이트리밋/지연 제약 참조.)

## 알고리즘
```
if initial == null: return null
current = initial
attempts = 0
while isStillInFlight(current.status()) and attempts < MAX_FILL_POLL_ATTEMPTS:
    sleep(FILL_POLL_DELAY_MS)
    try:
        refreshed = toss.getOrder(current.orderId())
        if refreshed != null: current = refreshed
    except RuntimeException as e:
        log.warn("체결 확인 재조회 실패(orderId={}): {}", current.orderId(), e)
        break   # 재조회 실패는 거래를 막지 않음 — 가진 값으로 계속
    attempts += 1
return current
```

`isStillInFlight(status)`: `status`가 `"PENDING"`, `"PARTIAL_FILLED"`, `"PENDING_CANCEL"`,
`"PENDING_REPLACE"` 중 하나면 `true`(토스 OpenAPI 스펙의 "OPEN 그룹" 상태값, 2026-10-08
`openapi.tossinvest.com/openapi-docs/latest/openapi.json` 확인). 그 외(`FILLED`, `CANCELED`,
`REJECTED` 등 "CLOSED 그룹" 값 포함, 알 수 없는 값도)는 `false`(더 기다릴 이유 없음 — 미지
상태코드를 무한정 재시도하지 않도록 안전한 기본값은 "재시도 중단").

## 에러/실패
- `toss.getOrder()` 예외 → 즉시 루프 중단, 그때까지 확보한 `current` 반환(§9, 재시도 실패가
  거래 흐름을 막으면 안 된다는 기존 원칙).
- `refreshed == null`(응답 바디 없음) → `current` 갱신 안 하고 다음 재시도로.
- 4회 소진 후에도 진행 중 상태 → 에러 아님, 그 시점 값 그대로 반환(호출부의
  `actualFilledPrice`가 이미 "averageFilledPrice null이면 견적가 폴백"을 처리하므로 이중
  안전장치).

## 호출부 연동
```java
TossOrder order = toss.placeOrder(...);
order = waitForFill(order);
tossOrderId = order == null ? null : order.orderId();
filledPrice = actualFilledPrice(order, currentPrice);
```
(`order` 변수를 재할당 — `actualFilledPrice`는 수정 없이 그대로 재사용.)

## 테스트 계획
1. **즉시 FILLED**: `placeOrder()`가 `status="FILLED"`인 주문을 반환 → `waitForFill`이
   `toss.getOrder()`를 호출하지 않고 즉시 같은 객체 반환(`verify(toss, never()).getOrder(any())`).
2. **PENDING → FILLED(1회 재조회)**: 초기 `status="PENDING"`, `getOrder()` 1차 호출에서
   `status="FILLED"`+실제 체결가 반환 → 그 체결가가 쓰인다.
3. **재시도 소진**: `getOrder()`가 매번 `PENDING`만 반환 → 정확히 4회 호출 후 마지막 값으로
   진행(예외 없음, 거래 성공 처리 그대로 유지).
4. **재조회 예외**: `getOrder()`가 `RuntimeException` 던짐 → 루프 즉시 중단, `initial` 값으로
   거래가 계속 성공 처리됨(재조회 실패가 매수/매도 자체를 실패시키지 않음).

테스트에서 `Thread.sleep(300ms)` 누적 지연을 피하려면: 케이스 1은 재조회 자체가 없어 즉시,
케이스 2는 1회(300ms)뿐, 케이스 3·4도 소량의 sleep만 발생 — 전체 스위트에 유의미한 지연을
주지 않는 선에서 설계됨(4회×300ms=1.2초짜리 케이스는 3번 하나뿐).
