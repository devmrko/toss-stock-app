# 설계서: 자동매매 엔진 — 5종목 슬롯, 저평가+호재+인기 룰, 고정%추적손절, 시장상황 게이트, 드라이런/서킷브레이커 (#808)

> **상태**: Approved <!-- Draft | Approved | Superseded -->
> **작성**: [AI] Architect · **최종수정**: 2026-09-22
> **추적성** — Redmine: #808 · 관련: #802(buying-power, X-Tossinvest-Account=accountSeq 확정), #409(주문 API 원래 범위 밖으로 뒀던 이유)
> · 구현 파일(예정): `src/main/java/com/cloudhandson/tossstock/autotrade/**`, `toss/TossApiClient.java`(주문 메서드 추가)
> · 테스트(예정): `src/test/java/com/cloudhandson/tossstock/autotrade/**`

## 1. 목적 (Why)
대화 중 검증한 룰(저평가+호재+인기 매수, 고정% 추적손절 매도)로 실 예산 **500만원**(5종목×100만원)을 자동매매한다. 사용자가 "완전 자동매매"를 명시적으로 선택했고(AskUserQuestion 응답), 드라이런 선행과 서킷브레이커를 조건으로 진행 승인함.

목표(1줄): "정해진 룰이 실 계좌에서 안전 한도 안에서 자동으로 사고 팔되, 사람이 언제든 멈출 수 있다."

## 2. 범위 (Scope)
- **포함**:
  - `TossApiClient`에 주문 메서드 추가(`placeOrder`, `cancelOrder`, `getOrder`, `getSellableQuantity`) — 읽기 전용이던 #409 범위를 처음으로 넘어감.
  - 5종목 슬롯 관리(`auto_trade_position`), 전역 상태·서킷브레이커(`auto_trade_state`).
  - 매수 신호: 저평가(수동 큐레이션 플래그, §12 참고) + 호재(뉴스 S4↑) + 인기(거래량 20일 평균 대비 스파이크) + **시장상황 게이트**(신규 요구사항 — breadth/뉴스 기반 매수 차단).
  - 매도 신호: 고정% 추적손절(하드 -10%, 추적 -10%/-15~20%) 또는 호재 소멸(해당 종목 최근 뉴스가 S2↓로 하락).
  - **드라이런 모드**(기본값 ON): 신호·주문 의도를 로그/DB에 기록만 하고 실제 주문 API 호출 안 함.
  - **서킷브레이커**: 전체 평가손익이 초기 예산 대비 -15% 도달 시 즉시 전체 중단(매수 차단, 기존 포지션은 매도만 허용) + Discord 긴급 알림. 재개는 설정 수동 변경만.
  - 종목당 예산 상한(100만원) 하드캡 — 초과 주문 코드 레벨 차단.
  - Discord 알림(매수/매도 체결, 서킷브레이커 발동, 드라이런 신호).
- **제외 (out of scope, 후속 이슈)**:
  - **저평가 자동 판정(PBR/PER 실시간 피드)** — 데이터 소스 없음(§12 미해결 질문에서 이미 확인됨). v1은 **수동 큐레이션 테이블**(`auto_trade_candidate`)에 미리 등록된 종목만 대상으로 함. 완전 자동 스크리닝은 후속 이슈.
  - OCO/OTO 조건주문 — 이번엔 단순 시장가/지정가만.
  - 다계좌 지원 — #802와 동일하게 `getAccounts()` 첫 계좌만.
  - 세금/환전 계산 — 실제 손익은 사용자가 토스 앱에서 별도 확인.

## 3. 인수조건 (Acceptance Criteria)
- [ ] `application.yml` 기본값 `auto-trade.dry-run=true` — 명시적으로 `false`로 바꾸지 않는 한 `TossApiClient.placeOrder`가 절대 호출되지 않음(단위테스트로 강제).
- [ ] 종목당 주문 금액이 `perSymbolBudget`(100만원)을 초과하면 주문 생성 자체가 차단되고 예외/로그.
- [ ] 서킷브레이커 조건(총 평가손익 ≤ -15%) 충족 시: (a) 신규 매수 전부 차단 (b) 기존 포지션 매도는 허용(손실 확정/청산 가능해야 함) (c) Discord 알림 발송 (d) `auto_trade_state.circuit_breaker_tripped=1` 영속화, 재기동해도 유지.
- [ ] `POST /api/autotrade/test`(또는 스케줄러 강제 1회 실행) → 드라이런 모드에서 신호가 로그/DB에 남고 실주문은 0건.
- [ ] `TrailingStopCalculator`, `CircuitBreaker`, `MarketRegimeGate` 단위 테스트 통과(경계값 포함).
- [ ] 통합 테스트: `.env` 키 있을 때만 실행되는 실제 주문 API 호출 테스트는 **작성하지 않음**(운영 계좌 오염 위험 — 드라이런 로직만 목(mock)으로 검증).

## 4. 컨텍스트 & 제약
- **주문 API(실측 필요)**: 토스 OpenAPI 공식문서 기준 `POST /api/v1/orders`(시장가/지정가), `POST /api/v1/orders/{id}/modify`, `POST /api/v1/orders/{id}/cancel`, `GET /api/v1/orders`, `GET /api/v1/orders/{id}` — 헤더 `X-Tossinvest-Account`(accountSeq, #802 확정) 필요. **주의**: 이 스펙은 문서(overview.md) 조회로만 확인했고 #802처럼 실제 호출 검증은 아직 안 함 — Developer 단계에서 실제 요청/응답 스키마(요청 바디 필드명, 시장가 주문 시 `orderType` 값 등)를 반드시 재검증할 것(전역 규칙: 문서 서술만 믿지 말고 실제 호출로 확인).
- **레이트리밋**: ORDER 그룹 초당 10건, ORDER_INFO 그룹 초당 6건(장 시작 09:00~09:10엔 3건) — 스케줄러 틱 간격을 이 안에서 설계.
- **고액주문 확인 플래그**: 문서상 "일정 금액 초과 시 `confirmHighValueOrder: true` 필요"라는 언급이 있었으나 정확한 임계값은 재검증 필요. 우리 종목당 상한이 100만원이라 어차피 해당될 가능성은 낮지만, Developer 단계에서 실제 스펙 재확인.
- **가정**: 사용자가 이미 원칙 문서(§3/§4/§13)와 이번 대화의 백테스트 결과에 동의 — ATR/클러스터링/공매도·레버리지 팩터는 **채택하지 않음**(실험 결과 우위 없음).

## 5. 아키텍처 개요
```
[AutoTradeScheduler] (@Scheduled, 장중 주기 실행, cron 설정)
  │
  ├─► [MarketRegimeGate.evaluate] ──uses──► MarketOverviewClient(/api/market/overview), NewsMapper
  │        (breadth/시장 뉴스로 "신규 매수 허용?" 판정 — 매도는 게이트와 무관하게 항상 동작)
  │
  ├─► [PositionMonitor] (보유 5슬롯 순회)
  │        ├─► TrailingStopCalculator.decide(current, peak, avgCost, ...) → SELL/HOLD
  │        ├─► 호재소멸 판정(NewsMapper 최근 sentiment 조회) → SELL/HOLD
  │        └─► SELL 시 → OrderExecutor.sell(...)
  │
  ├─► [CandidateScanner] (빈 슬롯 있을 때만, 게이트 열려있을 때만)
  │        ├─► auto_trade_candidate(수동 큐레이션) 조회
  │        ├─► 호재(S4↑) + 인기(거래량 스파이크) 재확인
  │        └─► 통과 시 → OrderExecutor.buy(...)
  │
  ├─► [CircuitBreaker.check] (매 틱, 전체 평가손익 계산 후)
  │        └─► 트립 시 → auto_trade_state 갱신 + Discord 긴급알림 + 이후 매수 전부 차단
  │
  └─► [OrderExecutor]
           ├─► dryRun=true  → auto_trade_order_log 에 "의도"만 기록, TossApiClient 호출 안 함
           └─► dryRun=false → TossApiClient.placeOrder(...) 실호출 + 결과 기록 + Discord 알림
```
- **I/O ↔ 순수 로직 경계**: `TrailingStopCalculator`, `CircuitBreaker`, `MarketRegimeGate.decide`(입력을 이미 받은 순수 판정 부분)는 순수 함수로 분리해 단위테스트. HTTP/DB 호출은 `OrderExecutor`/`*Mapper`/`TossApiClient`에 격리.

## 6. 데이터 모델

### `auto_trade_candidate` (수동 큐레이션 후보 — v1은 자동 스크리닝 없음)
| 컬럼 | 타입 | 설명 |
|---|---|---|
| id | NUMBER IDENTITY PK | |
| symbol | VARCHAR2(20) | KR 6자리 또는 US 티커 |
| market | VARCHAR2(10) | 'KR' / 'US' |
| valuation_note | VARCHAR2(500) | 저평가 판단 근거(사람이 적음, 예: "PBR 1.44/PER 15.5, 업종평균 대비 낮음 — 2026-09-19 조사") |
| valuation_checked_at | TIMESTAMP | 마지막 저평가 확인 시각(수동 갱신 필요 — 자동 아님을 명확히) |
| active | NUMBER(1) | 후보 유효 여부 |
| created_at | TIMESTAMP | |

### `auto_trade_position` (5슬롯 실제 보유)
| 컬럼 | 타입 | 설명 |
|---|---|---|
| id | NUMBER IDENTITY PK | |
| symbol | VARCHAR2(20) | |
| market | VARCHAR2(10) | |
| status | VARCHAR2(20) | HOLDING / EXITED |
| entry_price | NUMBER | |
| entry_qty | NUMBER | |
| entry_at | TIMESTAMP | |
| peak_price | NUMBER | 진입 후 고점(추적손절 기준) |
| budget_allocated | NUMBER | 배정 예산(≤ perSymbolBudget) |
| exit_price | NUMBER | NULL이면 보유 중 |
| exit_reason | VARCHAR2(30) | HARD_STOP / TRAIL_STOP / NEWS_FADED / MANUAL / CIRCUIT_BREAKER |
| exit_at | TIMESTAMP | |
| dry_run | NUMBER(1) | 이 포지션이 드라이런으로 생성됐는지(실주문과 혼동 방지) |
| created_at | TIMESTAMP | |

### `auto_trade_state` (싱글턴, id=1 고정)
| 컬럼 | 타입 | 설명 |
|---|---|---|
| id | NUMBER PK | 항상 1 |
| total_budget | NUMBER | 500만원 |
| per_symbol_budget | NUMBER | 100만원 |
| dry_run | NUMBER(1) | 전역 드라이런 여부(코드 기본값과 별개로 DB에서도 재확인 — 이중 안전장치) |
| circuit_breaker_tripped | NUMBER(1) | |
| circuit_breaker_tripped_at | TIMESTAMP | |
| updated_at | TIMESTAMP | |

### `auto_trade_order_log` (드라이런 포함 모든 신호/주문 이력 — 감사 로그)
| 컬럼 | 타입 | 설명 |
|---|---|---|
| id | NUMBER IDENTITY PK | |
| symbol | VARCHAR2(20) | |
| side | VARCHAR2(10) | BUY / SELL |
| reason | VARCHAR2(30) | BUY_SIGNAL / HARD_STOP / TRAIL_STOP / NEWS_FADED / CIRCUIT_BREAKER |
| dry_run | NUMBER(1) | |
| requested_qty | NUMBER | |
| requested_price | NUMBER | NULL이면 시장가 |
| toss_order_id | VARCHAR2(50) | 실주문일 때 토스 응답 orderId, 드라이런이면 NULL |
| created_at | TIMESTAMP | |

## 7. 함수 명세 (Function Specs)

| 함수 | 책임(1줄) | 시그니처(잠정) | 복잡? |
|------|-----------|----------------|-------|
| `TrailingStopCalculator.decide` | 하드/추적손절 매도 판정 | `ExitReason decide(BigDecimal current, BigDecimal peak, BigDecimal avgCost, double hardStopPct, double trailPct)` | **복잡** → `fn-trailing-stop.md` |
| `CircuitBreaker.check` | 전체 손익 기준 서킷브레이커 판정 | `boolean check(BigDecimal currentEquity, BigDecimal initialBudget, double thresholdPct)` | 단순(경계값만 조심) |
| `MarketRegimeGate.evaluate` | 시장상황 기반 신규매수 허용 여부 | `boolean evaluate(MarketOverview overview, List<NewsSignal> recentMarketNews)` | **복잡** → `fn-market-regime-gate.md` |
| `NewsFadeDetector.hasNewsFaded` | 보유 종목 호재 소멸 여부 | `boolean hasNewsFaded(String symbol, int lookbackDays)` | 단순(NewsMapper 조회+비교) |
| `OrderExecutor.buy` / `.sell` | 드라이런 분기 포함 주문 실행 I/O | `OrderResult buy(String symbol, BigDecimal budget)` 등 | **복잡** → `fn-order-executor.md` |
| `TossApiClient.placeOrder` | 주문 API 패스스루 | `TossOrder placeOrder(OrderRequest req)` | 단순 패스스루(단, 실계좌 영향 크므로 리뷰 강화) |
| `AutoTradeScheduler.tick` | 한 틱의 전체 흐름 오케스트레이션 | `void tick()` | 단순(위 함수들 호출만, 로직 없음) |

## 8. 흐름 / 알고리즘
1. `@Scheduled` 틱(장중, 예: 5분 간격 — 레이트리밋·API 비용 고려해 재진입알림의 1분보다 느리게) 진입.
2. `auto_trade_state` 로드. `circuit_breaker_tripped=1`이면 매도 로직만 수행, 매수 전부 스킵.
3. 보유 포지션(`status=HOLDING`) 각각에 대해 `TrailingStopCalculator.decide` + `NewsFadeDetector` 판정 → SELL이면 `OrderExecutor.sell` 호출(드라이런/실주문 분기) → `auto_trade_position` 갱신 + Discord 알림.
4. 매도 후(또는 매도 대상 없을 시) `CircuitBreaker.check`로 전체 평가손익 재계산 → 트립 시 `auto_trade_state` 갱신 + 긴급 알림 + **이번 틱은 여기서 매수 단계 스킵**.
5. 트립 안 됐고 빈 슬롯(5 - 현재 HOLDING 수) 있으면 `MarketRegimeGate.evaluate` 확인.
6. 게이트 열려있으면 `auto_trade_candidate`(active=1) 순회 → 호재(S4↑)·인기(거래량 스파이크) 재확인 통과 종목 → `OrderExecutor.buy` → `auto_trade_position` 신규 행 생성 + Discord 알림.
7. 모든 신호(매수 스킵 포함)는 `auto_trade_order_log`에 기록(드라이런 여부와 무관하게 — 사후 분석용).

## 9. 엣지케이스 & 에러 처리
- **드라이런 이중 안전장치**: `application.yml` 기본값 true **+** DB `auto_trade_state.dry_run` 컬럼도 별도 존재 — `OrderExecutor`는 **둘 다** true여야만 드라이런으로 간주하지 않고(즉 하나라도 false면 안전 쪽인 "드라이런"으로 처리) 실주문. 설정 실수로 한쪽만 바뀌어도 실주문 안 나가게.
- **주문 API 실패**(4xx/5xx): 재시도 금지(금액이 걸린 주문을 함부로 재시도하면 중복 체결 위험) — 실패 로그 남기고 다음 틱에서 재판단.
- **부분 체결/미체결**: v1은 시장가 주문만 사용해 체결 지연 리스크 최소화(지정가 미체결 관리 로직은 범위 밖).
- **동시 실행 방지**: 스케줄러 틱이 이전 틱보다 오래 걸리는 경우 대비 — `@Scheduled`는 기본적으로 순차 실행(Spring 기본 단일 스레드 TaskScheduler)이라 별도 락 불필요(재진입알림과 동일 가정).
- **서킷브레이커 트립 중 매도 허용**: 손실 확정을 막지 않기 위해 트립 후에도 기존 포지션의 손절 로직은 계속 동작.
- **환율(US 종목)**: v1은 원화 환산 없이 각 통화 기준으로만 손익 계산·표시(정확한 통합 손익률은 후속 과제로 §12에 남김).

## 10. 테스트 계획
- **단위**: `TrailingStopCalculatorTest`(하드손절 경계, 추적손절 경계, 고점 갱신), `CircuitBreakerTest`(-15% 정확히 경계, 그 이전/이후), `MarketRegimeGateTest`(breadth 낮음+부정 뉴스 → 차단, 정상 → 허용).
- **OrderExecutor**: `TossApiClient`를 목(mock)으로 대체 — dryRun=true일 때 `placeOrder` 호출 0회 검증(가장 중요한 안전 테스트), dryRun=false일 때 정확히 요청된 파라미터로 1회 호출 검증.
- **통합**: 실제 주문 API 호출 테스트는 작성하지 않음(§4).
- **수동 검증(배포 전 필수)**: dryRun=true 상태로 최소 1~2주 운영, `auto_trade_order_log`를 매일 육안 확인 후 dryRun=false 전환은 **사용자 승인 하에 수동으로만**.

## 11. 리스크 & 대안 검토
- **왜 완전자동인데도 드라이런을 강제하나**: 사용자가 "기준은 운영하면서 조정해보자"고 했고, 지금까지의 전략 검증이 전부 과거 데이터 백테스트뿐이라 실전 신호 타이밍 자체가 검증된 적이 없음 — 실제 돈이 나가기 전에 최소 한 번은 "신호만 보고 사람이 확인"하는 단계가 안전공학적으로 필수라고 판단(Architect 권고, 사용자도 §11 turn에서 암묵 동의 — "그대로 진행해줘" 응답).
- **저평가 자동판정 — 2026-09-25 해소**: 원래 "데이터 소스 없음"으로 보류했으나 사용자 지적으로 재확인, 네이버(KR)·야후(US) 비공식 API로 실시간 PER/PBR 확보 가능함을 실측 확인. `ValuationClient`로 구현·연결 완료(fn-valuation-client.md). 다만 후보 발굴(어떤 종목을 볼지) 자체는 여전히 `auto_trade_candidate` 수동 큐레이션.
- **원칙 §3 체크리스트 과소단순화 — 2026-09-25 지적·수정**: 초기 "인기+호재+저평가" 3조건은 대화 중 임의로 만든 단순화였고, 실제 §3의 7개 항목(이야기/실적의질/재무건전성/자본배분/거버넌스/시장성/가격추세) 중 손절룰 말고는 거의 반영이 안 됐었음. 자동화 가능한 5항목(실적/재무/자본배분/시장성/상대강도)을 `FundamentalScore`로 추가 구현 — "대부분 YES"(전부 아님) 원문에 맞춰 5개 중 4개 이상 통과 요구.
- **주문 API 안 써본 스펙에 의존**: #409에서 의도적으로 미구현했던 영역. Developer 단계에서 실제 소액 시장가 주문 1건으로 요청/응답 스키마 재검증 필수(문서만 믿지 않기 — 전역 규칙).
- **레짐게이트 임계값**: breadthPct/뉴스 감정 기준값은 초기 추정치로 시작, "운영하면서 조정"(사용자 명시적 요청) — 하드코딩하지 말고 `application.yml`에서 조정 가능하게.

## 12. 미해결 질문 (Open Questions)
- ~~저평가 자동 스크리닝~~ → 2026-09-25 해소(ValuationClient, 네이버/야후).
- 미국 종목 환율 통합 손익 계산 — v1은 통화별 별도 표시로 우회.
- 주문 API 실제 요청/응답 스키마(필드명, enum 값) — Developer 단계 실측 필수.
- 시장상황 게이트 임계값(breadthPct 몇 % 이하면 차단할지 등) 초기값 — 운영하며 조정 예정(사용자 합의).
- **여전히 미자동화**(§3 체크리스트 잔여 항목): 1.이야기(론, 촉매의 질적 판단) — 호재뉴스 S4↑로만 대충 대체 중, 5.거버넌스/리스크(5년 분쟁·소송 이력) — 데이터 소스 없음, 재무건전성 중 "유증/회사채 남발 없음"(발행 이력 필요) — 부채비율만 봄, 자본배분 중 자사주매입/소각(공시 이력 필요) — 배당만 봄. 전부 후속 과제.
- `FundamentalScore`의 min-fundamental-pass=4(5개 중) 초기값 — ATR/거래량스파이크 배수처럼 확정 아님, 운영 데이터 쌓이면 조정.
