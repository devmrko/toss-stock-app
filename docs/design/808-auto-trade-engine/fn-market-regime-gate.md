# 함수 설계서: `MarketRegimeGate.evaluate` (#808)

> **부모 설계서**: ./README.md · **상태**: Approved
> **작성**: [AI] Architect · **구현**: `com.cloudhandson.tossstock.autotrade.MarketRegimeGate#evaluate` (TBD) · **테스트**: `MarketRegimeGateTest`(TBD)

## 1. 시그니처
```java
boolean evaluate(MarketOverview overview, List<NewsSignal> recentMarketNews, MarketGateProperties props)
// true = 신규 매수 허용, false = 차단(기존 포지션 매도는 이 함수와 무관하게 항상 동작)
```

## 2. 책임 (단일 책임, 1줄)
시장 폭(breadth)과 최근 시장 전반(targets=MARKET) 뉴스 감정을 근거로 "지금 신규 매수를 열어도 되는 시장 상황인가"를 판정한다 — 사용자가 명시적으로 요청한 "뉴스에 따라 시장 상황을 반영" 요구사항.

## 3. 입력
| 파라미터 | 타입 | 제약/검증 | 설명 |
|----------|------|-----------|------|
| `overview` | MarketOverview | null 불가 | `/api/market/overview` 응답(breadthPct, marketLevel, marketNote) |
| `recentMarketNews` | List<NewsSignal> | null 불가(빈 리스트 허용) | 최근 `lookbackDays`일 내 `targets`에 `MARKET` 포함된 뉴스의 sentiment 목록 |
| `props` | MarketGateProperties | - | 임계값 설정(아래 §9) — 하드코딩 금지, `application.yml`에서 조정 |

## 4. 출력
- **반환**: `boolean`. 아래 두 조건을 **모두** 만족해야 `true`(매수 허용) — 원칙 §13식으로 "허용 근거"와 "차단 근거"를 각각 확인하는 보수적 설계(하나라도 위험 신호면 막는 쪽).
- **부수효과**: 없음 — **순수 함수**(오버뷰·뉴스 조회는 호출측 책임).

## 5. 동작 / 알고리즘
1. **breadth 조건**: `overview.breadthPct() >= props.minBreadthPct()`(기본값 35 — 대화 중 관찰된 실측 사례: breadthPct 28~33%대일 때 약세 우위였음, 초기값은 그 근방에서 시작해 "운영하면서 조정").
2. **뉴스 조건**: `recentMarketNews` 중 `sentiment`가 S1(강한 악재)인 항목이 `props.maxS1Count()`(기본값 1) 건 초과면 차단.
3. 위 둘 다 통과해야 `true`. 하나라도 실패하면 `false`.
4. `recentMarketNews`가 비어있으면(뉴스 자체가 아직 안 잡힘) 뉴스 조건은 통과로 간주(정보 없음 ≠ 위험 — breadth만으로 판단).

## 6. 에러 & 실패 모드
| 조건 | 처리 | 반환/예외 |
|------|------|-----------|
| `overview == null` | 데이터 조회 실패 시 안전 쪽(차단) | 호출측에서 null 체크 후 `evaluate` 호출 전 `false` 반환(이 함수엔 null 전달 안 함) |
| `overview.breadthPct()`가 이상치(음수/100 초과) | 방어적 체크 | `IllegalArgumentException`(조용히 삼키지 않음) |

## 7. 엣지케이스
- breadthPct 정확히 임계값과 같을 때: `>=`로 통과(경계 포함 허용 쪽).
- S1 뉴스가 정확히 `maxS1Count`개: 통과(초과일 때만 차단, `>` 사용).
- 뉴스 목록에 `MARKET` 외 타겟(개별 종목/섹터)만 있고 `MARKET` 자체는 없음: 호출측이 이미 필터링해서 넘긴다는 전제(이 함수는 필터링된 리스트만 받음) — 필터링은 호출측(`AutoTradeScheduler` 또는 별도 어댑터)의 책임.

## 8. 복잡도 / 성능
- O(n), n = 최근 뉴스 건수(보통 수십 건 이하). 스케줄러 틱마다 1회 — 성능 이슈 없음.

## 9. 의존성
- `application.yml`의 `auto-trade.gate.min-breadth-pct`(기본 35), `auto-trade.gate.max-s1-count`(기본 1), `auto-trade.gate.lookback-days`(기본 3) — 사용자가 "운영하면서 조정하자"고 명시했으므로 재배포 없이 값만 바꿀 수 있어야 함.
- 호출측이 `/api/market/overview`와 `NewsMapper`(또는 동등 조회)로 입력을 만들어 전달.

## 10. 테스트 케이스
- [ ] 정상: breadth=50, S1 뉴스 0건 → `true`
- [ ] 차단: breadth=28(관찰된 약세장 실측치) → `false`
- [ ] 차단: breadth=50이지만 S1 뉴스 2건 → `false`
- [ ] 경계: breadth == minBreadthPct 정확히 일치 → `true`
- [ ] 경계: S1 뉴스 정확히 maxS1Count건 → `true`, +1건 → `false`
- [ ] 엣지: 뉴스 리스트 비어있음 + breadth 통과 → `true`
- [ ] 실패: breadthPct = -5 또는 150 → `IllegalArgumentException`

## 11. 추적성
- 인수조건: #808 "시장상황 게이트" 요구사항(사용자 명시 요청: "뉴스에 따라 시장 상황을 반영").
- 관련 ADR: 없음. 임계값은 초기 추정치 — §12 미해결 질문에 운영 중 조정 예정으로 명시.
