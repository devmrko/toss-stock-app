# 함수 설계서: `OrderExecutor.buy` / `OrderExecutor.sell` (#808)

> **부모 설계서**: ./README.md · **상태**: Approved
> **작성**: [AI] Architect · **구현**: `com.cloudhandson.tossstock.autotrade.OrderExecutor` (TBD) · **테스트**: `OrderExecutorTest`(TBD, TossApiClient mock)

## 1. 시그니처
```java
OrderResult buy(String symbol, String market, BigDecimal budget)
OrderResult sell(Long positionId, String reason)
// OrderResult(boolean success, boolean dryRun, String tossOrderId, String message)
```

## 2. 책임 (단일 책임, 1줄)
매수/매도 "의도"를 받아 드라이런 여부에 따라 실제 주문 호출 또는 로그 기록만 수행하고, 결과를 `auto_trade_position`/`auto_trade_order_log`에 반영한다 — 이 시스템에서 **실제 돈이 움직이는 유일한 지점**이라 가장 엄격하게 다룬다.

## 3. 입력
| 파라미터 | 타입 | 제약/검증 | 설명 |
|----------|------|-----------|------|
| `symbol` | String | 공백 아님 | 종목코드/티커 |
| `market` | String | 'KR' 또는 'US' | 시장 구분(주문 API 라우팅에 필요할 수 있음 — Developer 단계 실측) |
| `budget` | BigDecimal | `0 < budget <= perSymbolBudget`(100만원) | 이 매수에 배정할 금액. **상한 초과 시 주문 자체를 만들지 않음**(§9 하드캡) |
| `positionId` | Long | 존재하는 HOLDING 포지션 | 매도 대상 |
| `reason` | String | `ExitReason`/`CIRCUIT_BREAKER`/`MANUAL` 중 하나 | 매도 사유(로그·알림용) |

## 4. 출력
- **반환**: `OrderResult` — 성공/실패, 드라이런 여부, (실주문이면) 토스 주문ID, 사람이 읽을 메시지.
- **부수효과**:
  - **드라이런**: `auto_trade_order_log`에 1행 INSERT(`dry_run=1`, `toss_order_id=NULL`). `TossApiClient.placeOrder` **호출 안 함**(가장 중요한 불변식).
  - **실주문**: `TossApiClient.placeOrder` 호출 → 성공 시 `auto_trade_position` INSERT/UPDATE + `auto_trade_order_log`(`dry_run=0`, `toss_order_id`=응답값) + `DiscordClient.send`로 체결 알림.

## 5. 동작 / 알고리즘 (buy)
1. `budget > perSymbolBudget`이면 **주문 생성 자체를 하지 않고** 즉시 실패 반환(`IllegalArgumentException`을 잡아서 `OrderResult(success=false, ...)`으로 변환 — 호출측 흐름 안 끊음, 단 로그는 남김).
2. `auto_trade_state` 재조회: `dryRun=true` **OR** DB `dry_run=1` **중 하나라도**면 드라이런 처리(§9 이중 안전장치 — README §9와 동일 원칙).
3. **드라이런**: `auto_trade_order_log` INSERT 후 종료. 실제 포지션(`auto_trade_position`)도 `dry_run=1`로 생성해서 이후 틱에서 "가상 보유"로 계속 추적(추적손절 로직도 가상으로 검증 가능하게).
4. **실주문**: `TossApiClient.placeOrder(symbol, market, 시장가, budget 기반 수량)` 호출 → 성공 시 `auto_trade_position`(`dry_run=0`) 생성 + 알림. 실패(4xx/5xx) 시 재시도 없이 실패 기록 + 알림(§9 에러 처리).

## 5-1. 동작 / 알고리즘 (sell)
1. `positionId`로 포지션 조회, 없거나 이미 EXITED면 실패 반환.
2. 해당 포지션의 `dry_run` 플래그를 따름(드라이런으로 생성된 가상 포지션은 매도도 가상으로) — buy 시점의 dry_run과 매도 시점의 전역 설정이 달라도 **포지션 자체의 dry_run을 우선**(진입이 가상인데 청산이 실주문이 되는 모순 방지).
3. 드라이런/실주문 분기는 buy와 동일한 이중 안전장치 원칙.
4. 성공 시 `auto_trade_position.status=EXITED`, `exit_price`/`exit_reason`/`exit_at` 갱신 + `auto_trade_order_log` 기록 + Discord 알림(사유 포함).

## 6. 에러 & 실패 모드
| 조건 | 처리 | 반환/예외 |
|------|------|-----------|
| `budget > perSymbolBudget` | 주문 미생성 | `OrderResult(success=false, message="예산 상한 초과")` |
| `TossApiClient.placeOrder` 4xx/5xx | 재시도 안 함(중복 체결 위험) | `OrderResult(success=false, ...)` + 실패 로그, 다음 틱에서 재판단 |
| 매도 대상 포지션 없음/이미 EXITED | 무시 아님 — 명시 실패 | `OrderResult(success=false, message="포지션 없음")` |
| Discord 알림 전송 실패 | 주문 성공 자체는 되돌리지 않음(알림은 부가기능) | 로그만, `OrderResult`는 success 유지 |
| `auto_trade_order_log` INSERT 실패(예: 컬럼 길이초과 등 DB 레벨 예외) | **2026-10-01 수정**: 감사로그 기록은 예외를 흡수(`saveLogSafely`) — 실주문이 이미 체결됐다면 포지션 기록(`positionMapper.insert`)은 반드시 계속 진행 | 감사로그 1건 유실(ERROR 로그로만 남음), 포지션/흐름은 정상 |

## 7. 엣지케이스
- 서킷브레이커 트립 중 `sell` 호출: **허용**(README §9 — 손실 확정 경로는 막지 않음). `buy`는 호출측(`AutoTradeScheduler`)이 애초에 호출 안 함(이 함수 책임 아님, 호출 여부 판단은 상위 계층).
- 드라이런 포지션이 5슬롯을 다 차지해서 실제 슬롯 계산에 낄지 여부: **낀다**(드라이런도 "가상 보유"로 슬롯 점유 — 그래야 드라이런 기간 동안도 5종목 룰이 그대로 검증됨).
- 동일 종목 중복 매수 시도(이미 HOLDING인데 다시 buy): 호출측이 후보 스캔 시 이미 보유 중인 종목은 제외해야 함(이 함수는 방어하지 않음, 상위 책임).

## 8. 복잡도 / 성능
- O(1) DB/HTTP 호출. 틱당 최대 5회(포지션 수) 이내.

## 9. 의존성
- `TossApiClient.placeOrder`(2026-09-29 실주문 왕복으로 스펙 검증 완료).
- `AutoTradePositionMapper`, `AutoTradeOrderLogMapper`, `AutoTradeStateMapper`(신규 MyBatis 매퍼).
- `DiscordClient`(기존 재사용).
- `application.yml`의 `auto-trade.dry-run`, `auto-trade.per-symbol-budget`.

## 10. 테스트 케이스
- [ ] 정상(드라이런): `dryRun=true` → `TossApiClient.placeOrder` 호출 0회(mock verify), 로그 1건 생성, 포지션 `dry_run=1`로 생성.
- [ ] 정상(실주문): `dryRun=false` → `placeOrder` 정확히 1회, 파라미터 일치, 포지션 `dry_run=0`.
- [ ] 실패: `budget = perSymbolBudget + 1` → 주문 미생성, `placeOrder` 호출 0회.
- [ ] 실패: `placeOrder`가 예외 던짐 → 재시도 없이 1회만 시도, `OrderResult(success=false)`.
- [ ] 이중안전: 전역 `dryRun=false`인데 DB `auto_trade_state.dry_run=1`(불일치) → 드라이런으로 처리(안전 쪽 우선).
- [ ] sell: 이미 EXITED인 포지션 재매도 시도 → 실패.

## 11. 추적성
- 인수조건: #808 "dryRun=true일 때 placeOrder 호출 0회", "서킷브레이커 트립 시 매도 허용".
- 관련 ADR: 없음. **이 함수는 리뷰(06-Reviewer) 단계에서 특별히 꼼꼼히 볼 것** — 실제 자금 이동 지점.
- **치명 버그 — 2026-10-01 발견·해소**: 012330(현대모비스) 실매수가 2026-10-01 09:00~09:05 사이 5회 체결됐는데 `auto_trade_order_log`/`auto_trade_position` 둘 다 기록 0건이었던 실사례. 원인: Toss가 반환하는 실제 `orderId`가 86자인데 `toss_order_id` 컬럼이 `VARCHAR2(50)`이라 `saveLog` INSERT가 `ORA-12899`로 실패 → 예외가 `buy()`를 그 자리에서 중단시켜 `positionMapper.insert()`(§7에서 "상위 책임"이라 언급한 "이미 보유 중" 체크의 전제조건)까지 도달하지 못함 → 포지션이 없으니 다음 틱에서도 "이미 보유 중"으로 안 걸려 같은 종목을 반복 매수 → 실잔고 소진. 사용자가 실제 매수가능금액(Toss `/api/v1/buying-power`)과 보유수량(`/api/v1/sellable-quantity`)을 직접 조회해 발견("니가 샀다고"). 컬럼을 `VARCHAR2(200)`으로 넓히고, `saveLog`→`saveLogSafely`로 감사로그 실패가 포지션 기록을 막지 못하게 방어(§6) — 두 수정 중 후자가 더 근본적(향후 어떤 이유로 로그 INSERT가 실패해도 이 사고가 재발하지 않음). 실계좌 012330 실보유분은 `auto_trade_position`에 수동 reconcile(평단 377,650원, 10주).

## 12. 부록 — 결정근거 파라미터 추가(#832, 2026-10-06)
- 시그니처에 `String rationale`이 1개 추가됐다: `buy(symbol, market, budget, currentPrice, rationale)`,
  `sell(position, reason, currentPrice, rationale)`. 주문 실행·드라이런 분기·포지션 기록 등
  §6~§8의 제어흐름은 **변경 없음** — `message` 조합부만 `"<rationale> | <기존 문구>"`가 된다
  (`null`/공백이면 기존 문구 그대로, 500자 절삭 유지, 예산초과·수량0 경로는 기존 문구 유지).
- 설계: `docs/design/832-decision-rationale-logging/README.md` ·
  사양: `docs/reference/decision-rationale.md`.
