# 설계서: 매수/매도 결정근거 스냅샷 로깅 (#832)

> **상태**: Draft
> **작성**: [AI] Architect · **최종수정**: 2026-10-06
> **추적성** — Redmine: #832 · 관련 ADR: 없음
> · 구현 파일: `OrderExecutor.java`, `AutoTradeScheduler.java`, `RangeOrderExecutor.java`,
>   `RangeTradeScheduler.java`, 신규 `BuyRationale.java`/`SellRationale.java`(#808),
>   `RangeBuyRationale.java`/`RangeSellRationale.java`(#818) · 테스트: 각 대응 Test 클래스

## 1. 목적 (Why)
"우리 사면 기록하나 왜 샀는지 시스템에" → "추가해줘 그래야 나중에 파악하지"(사용자, 2026-10-06).
같은 날 사후검증 중 "판 결정이 모호한것 같다"는 추가 피드백도 있었음.

현재 `auto_trade_order_log`/`range_trade_order_log`는 `reason` 코드(enum)와 생성형 `message`
("실주문 체결"/"드라이런 — 실주문 안 함" 같은 기계적 문구)만 남긴다. 실제 판단에 쓰인 수치
(PER/PBR, 어느 OR-경로로 통과했는지, 펀더멘털 5항목 중 어떤 게 통과했는지, 인기 트리거가
거래량/가격 중 어느 쪽이고 수치가 얼마인지, 매도 시 하드스탑/트레일스탑 중 어느 쪽이
바인딩이었는지, 피크가·진입가 대비 수익률 등)는 전혀 기록되지 않는다.

이번 세션에서 034230/TMC(매수 안 한 이유)와 086450(매수한 이유) 사례를 사후에 재현하려고
외부 API를 다시 호출하며 수동으로 재구성했는데, 086450은 DB 데이터 윈도우가 그 사이
달라져서 끝내 완전히 재현되지 않았다 — 이 설계서가 풀려는 문제의 실증 사례.

## 2. 범위 (Scope)
- **포함**:
  - #808(모멘텀) `OrderExecutor.buy`/`sell` — 결정 시점에 이미 계산된 값으로 구조화된
    rationale 문자열을 만들어 `auto_trade_order_log.message`에 남긴다.
  - #818(레인지) `RangeOrderExecutor.buy`/`sell` — 동일 패턴, `range_trade_order_log.message`.
  - 신규 순수 포매터 클래스 4개(아래 §7) — 이미 호출부에 있는 값만 받아 문자열로 포맷.
- **제외 (out of scope)**:
  - 신규 외부 API 호출 추가(네이버/야후 등) — 레이트리밋/성능 영향 없이, 이미 계산된
    값(또는 이미 쓰는 로컬 DB 조회 1회 추가 정도)만 재사용한다.
  - 스키마 변경(신규 컬럼) — `message` VARCHAR2(500) 재사용(여유 충분, §6 참고).
  - 과거 로그 백필 — 이 설계는 신규 기록부터 적용. 과거 034230/TMC/086450 사례는 소급 불가.
  - `CapitalReturnCatalystDetector`/`PopularityChecker` 등 기존 판정 함수의 반환형 변경 —
    순수 판정 로직은 그대로 두고, rationale 포매터가 "같은 입력으로 다시 한번" 세부값만
    뽑아낸다(판정 자체를 건드리지 않아 리그레션 위험 최소화).

## 3. 인수조건 (Acceptance Criteria)
- [ ] #808 실제 매수 성공 시 `auto_trade_order_log.message`에 PER/PBR, 저평가 여부, 재평가
      촉매 매칭 키워드(있으면), 펀더멘털 5항목 통과/실패, 인기 트리거 종류+수치, 트리거
      뉴스 헤드라인이 포함된다.
- [ ] #808 매도 시 `message`에 하드스탑/트레일스탑/뉴스소멸 중 어느 게 발동했는지, 바인딩된
      stopPrice, 진입가·피크가·현재가, 진입가 대비·피크 대비 수익률(%)이 포함된다.
- [ ] #818 매수 시 `message`에 밴드 하단/상단, 매수상한선(entryCeiling), 밴드 내 위치(%)가
      포함된다.
- [ ] #818 매도 시 `message`에 PROFIT_TAKE/RANGE_BREAKDOWN/BAD_NEWS 중 어느 것이고, 해당
      임계선(profitFloor/breakdownFloor) 값과 진입가 대비 수익률이 포함된다.
- [ ] 예산상한초과/수량0/주문실패처럼 매수·매도 자체가 안 된 경로는 기존 message 그대로
      유지(이미 원인이 명확 — rationale 포매터는 "성공 경로"에만 적용).
  단, 매도쪽은 이미 어떤 이유로 매도를 "시도"했는지(exitReason)가 호출 전에 결정되므로,
  주문실패여도 어떤 exitReason이 트리거했는지는 message 앞부분에 남긴다.
- [ ] `message` 500바이트(VARCHAR2(500))를 넘지 않도록 길이 방어(초과 시 자름, 기존
      `truncate()` 패턴 재사용).
- [ ] 기존 테스트 전부 통과 + 신규 rationale 포매터 단위테스트 추가.

## 4. 컨텍스트 & 제약
- 의존성: 없음(신규 외부 I/O 없음) — 이미 `scanCandidates`/`processHolding`/
  `evaluateCandidate`(#818)에서 계산된 지역변수만 사용.
- 제약: `message` VARCHAR2(500) — 한글은 멀티바이트라 글자수 ≈ 바이트/3, 여유 있게
  150자 내외로 설계(아래 예시 참고).
- 가정: 포매터 함수는 **순수 함수**(입력→문자열, 부수효과 없음) — 단순(simple) 분류,
  `fn-*.md` 불필요(복잡 기준: 분기/상태기계·외부 I/O·리스크 경로·비자명 알고리즘 — 포매팅은
  해당 없음). `OrderExecutor.buy`/`sell` 자체의 주문 실행 로직은 변경하지 않고 파라미터만
  추가하므로, 그 함수들의 기존 `fn-order-executor.md`를 다시 쓸 필요는 없음(부록으로 한 줄
  추가만).

## 5. 아키텍처 개요
```
[AutoTradeScheduler.scanCandidates]          [AutoTradeScheduler.processHolding]
  이미 계산됨: valuation, cheap, rerateCatalyst,   이미 계산됨: current, peak, entryPrice,
  score(FundamentalScore), recent(인기판정용)       hardStopPct, trailStopPct, exitReason
        │                                                  │
        ▼                                                  ▼
  BuyRationale.describe(...)                       SellRationale.describe(...)
        │                                                  │
        ▼                                                  ▼
  orderExecutor.buy(symbol, market, budget,        orderExecutor.sell(position, exit,
                     price, rationale)                       price, rationale)
        │                                                  │
        ▼                                                  ▼
  saveLogSafely(..., message = rationale + " | " + outcome)
```
#818은 동일 구조를 `RangeBuyRationale`/`RangeSellRationale`로 복제(§2에서 이미 밝혔듯
#808 클래스를 공유하지 않는 기존 원칙 유지).

- I/O ↔ 순수 로직 경계: rationale 포매터는 순수 함수(BigDecimal/boolean/String 입력 →
  String 출력). `OrderExecutor`/`RangeOrderExecutor`는 그 결과를 받아 I/O(주문 실행,
  로그 insert)만 수행 — 기존 경계 그대로 유지.

## 6. 데이터 모델
- 변경 없음(신규 컬럼 없음). `message` 포맷:
  - BUY 성공: `"<rationale> | <outcome>"` 예) `"PER11.6/PBR0.47(저평가) 펀더3/5(실적X재무O배당O유동O강도X) 인기:가격+2.3% 뉴스:S5 \"안랩...급등\" | 실주문 체결"`
  - SELL 성공(#808): `"<rationale> | <outcome>"` 예) `"트레일스탑(피크235000→현재210500,-10.4%) 진입200000 수익+5.25%" | 실주문 체결"`
  - BUY 실패(예산초과/수량0): 기존 메시지 그대로("예산 상한 초과" 등) — 변경 없음.
  - 주문 자체가 실패(Toss API 예외): `"<rationale> | 주문 실패: <원인>"` — rationale은
    "왜 팔려고 시도했는지"는 남기고, 실패 원인은 기존처럼 뒤에 붙인다.
- 길이 방어: 기존 `truncate(String)`(500자 초과시 절삭) 그대로 재사용, rationale 조합 후
  최종 문자열에 1회 적용.

## 7. 함수 명세 (Function Specs)

| 함수 | 책임(1줄) | 시그니처(잠정) | 입력 | 출력 | 에러/실패 | 복잡? |
|------|-----------|----------------|------|------|-----------|-------|
| `BuyRationale.describe` | #808 매수 성공 근거 문자열 생성 | `static String describe(Valuation v, boolean cheap, boolean rerateCatalyst, String catalystHeadline, FundamentalScore score, boolean volumeSpike, double volumeRatio, boolean priceMove, double priceMovePct, String triggerNewsTitle)` | 이미 계산된 판정값들 | 150자 내외 String | null 입력은 "N/A"로 표기(예외 안 던짐) | 단순 |
| `SellRationale.describe` | #808 매도 근거 문자열 생성 | `static String describe(ExitReason reason, BigDecimal entryPrice, BigDecimal peakPrice, BigDecimal currentPrice, BigDecimal hardFloor, BigDecimal trailFloor)` | 이미 계산된 값들 | String | 0으로 나누기 방지(수익률 계산 가드) | 단순 |
| `RangeBuyRationale.describe` | #818 매수 근거 문자열 생성 | `static String describe(BigDecimal rangeLow, BigDecimal rangeHigh, BigDecimal entryCeiling, BigDecimal currentPrice)` | 밴드값 | String | 가드 동일 | 단순 |
| `RangeSellRationale.describe` | #818 매도 근거 문자열 생성 | `static String describe(RangeExitReason reason, BigDecimal entryPrice, BigDecimal rangeLow, BigDecimal rangeHigh, BigDecimal profitFloor, BigDecimal breakdownFloor, BigDecimal currentPrice)` | 밴드값 | String | 가드 동일 | 단순 |
| `AutoTradeScheduler.scanCandidates`(수정) | 매수 호출 전 rationale 조합 | 기존 시그니처 유지, 내부에서 `BuyRationale.describe(...)` 호출 후 `orderExecutor.buy(..., rationale)` | 기존 지역변수 | void | 기존과 동일 | 단순(기존 분기 구조 안 바뀜) |
| `AutoTradeScheduler.processHolding`(수정) | 매도 호출 전 rationale 조합 | 동일 패턴 | 기존 지역변수 | void | 기존과 동일 | 단순 |
| `OrderExecutor.buy`/`sell`(수정) | 파라미터 1개(`rationale`) 추가, message 조합부만 변경 | `buy(String, String, BigDecimal, BigDecimal, String rationale)`, `sell(AutoTradePosition, ExitReason, BigDecimal, String rationale)` | 기존 + rationale | 기존과 동일(boolean) | 기존과 동일(실주문 경로 변경 없음) | 단순(제어흐름 불변) |
| `RangeOrderExecutor.buy`/`sell`(수정) | 동일 | `buy(String, BigDecimal, BigDecimal, BigDecimal, BigDecimal, String rationale)`, `sell(RangeTradePosition, RangeExitReason, BigDecimal, String rationale)` | 기존 + rationale | 기존과 동일 | 기존과 동일 | 단순 |

### 7-1. 구현 중 추가 등재 (2026-10-06, Developer)

§8이 요구한 "같은 공식으로 세부값 재계산"을 **판정 함수와 같은 자리에서** 하기 위해, 아래 순수
접근자 4개를 추가 등재한다(기존 판정 함수의 시그니처·반환형·판정 결과는 그대로. 스케줄러에
공식을 복제하는 쪽이 드리프트 위험이 더 커서 이쪽을 택했다).

| 함수 | 책임(1줄) | 시그니처 | 출력 | 에러/실패 | 복잡? |
|------|-----------|----------|------|-----------|-------|
| `PopularityChecker.volumeRatio` | 평균 대비 거래량 배수 추출(로깅용) | `static double volumeRatio(List<DailyOhlcv> rows, int window)` | double | 데이터 부족/평균0 → `0` | 단순 |
| `PopularityChecker.priceMovePct` | 당일 가격변동률(%) 추출(로깅용) | `static double priceMovePct(List<DailyOhlcv> rows)` | double | 데이터 부족 → `0` | 단순 |
| `TrailingStopCalculator.hardFloor` | 하드손절선 공개(기존 `decide` 내부식 그대로) | `static BigDecimal hardFloor(BigDecimal avgCost, double hardStopPct)` | BigDecimal | 입력 검증은 `decide` 책임 | 단순 |
| `TrailingStopCalculator.trailFloor` | 추적손절선 공개(동일) | `static BigDecimal trailFloor(BigDecimal peak, double trailPct)` | BigDecimal | 동일 | 단순 |

- `decide`는 이 두 접근자를 호출하도록 식만 추출했다(산술·MathContext·경계 동일). 판정 결과가
  바뀌지 않음은 `TrailingStopCalculatorTest`의 경계 테스트 + 신규 일치성 테스트로 묶었다.
- `volumeRatio`/`priceMovePct`는 기존 boolean 판정과 **같은 공식의 별도 함수**이므로, 임계값 비교
  결과가 boolean 판정과 일치하는지 `PopularityCheckerTest`에서 함께 검증한다(드리프트 방지).
- `BuyRationale.describe`의 `catalystHeadline`은 **호출부에서 비워 둔다**: 촉매 뉴스도 같은
  `active(symbol, 5)` 집합에서 나오므로 "뉴스:" 절과 문자열이 중복되고 따옴표가 중첩된다. 촉매
  경로는 `(재평가촉매)` 태그로 충분히 드러나고, 헤드라인은 "뉴스:" 절이 담당한다(포매터는 파라미터를
  계속 지원 — 별도 헤드라인을 아는 호출부가 생기면 그대로 쓸 수 있다).
- 스케줄러 내부 private 보조(설계 범위 안, 공개 API 아님): `AutoTradeScheduler#buyRationale`(값 조립),
  `AutoTradeScheduler#topActiveNewsLabel`(활성 뉴스 1회 조회 → `S5 "제목"`, 조회 실패는 삼키고
  `null` → "뉴스:N/A" — 로깅 때문에 매수가 막히면 안 되므로), `RangeTradeScheduler#sellRationale`.

> `hasRecentCatalyst`가 매칭된 키워드/헤드라인을 반환하지 않으므로, `catalystHeadline`/
> `triggerNewsTitle`은 rationale 조합 시점에 `newsMapper.active(symbol, 5)`를 한 번 더
> 조회해(로컬 DB, 외부 API 아님) 가장 높은 레벨의 활성 뉴스 제목을 뽑아 채운다. 이건
> 신규 DB 읽기 1회이지만 §2에서 말한 "신규 외부 API 호출"에는 해당하지 않음(이미 다른
> 메서드들도 같은 테이블을 매 틱마다 여러 번 읽는다).

## 8. 흐름 / 알고리즘
**#808 매수**: `scanCandidates`에서 모든 게이트를 통과해 `orderExecutor.buy(...)`를 호출하기
직전, 이미 가진 `valuation`/`cheap`/`rerateCatalyst`/`score`/`recent`로 `BuyRationale.describe`
호출 → 결과 문자열을 `buy()`의 새 파라미터로 전달 → `OrderExecutor`는 그 문자열을 그대로(또는
주문결과 접미사만 붙여) `message`에 저장.

**#808 매도**: `processHolding`에서 `TrailingStopCalculator.decide`가 반환한 `exit`과 이미 가진
`current`/`peak`/`entryPrice`로 `SellRationale.describe` 호출(하드/트레일 floor는 pure function
`TrailingStopCalculator`와 동일 공식으로 재계산 — 부수효과 없는 재계산이라 안전) → 매도 시도 시
파라미터로 전달.

**#818**은 동일 패턴, `RangeTradeSignal`의 `entryCeiling`/`profitFloor`/`breakdownFloor`를
rationale 조합 시 그대로 재사용(이미 `public static` pure function).

## 9. 엣지케이스 & 에러 처리
- 트리거 뉴스가 없는 경우(예: #828 유지 경로로 매수된 경우는 사실상 없음 — 매수는 항상
  "호재 살아있음" 조건을 거치므로 트리거 뉴스가 항상 존재. 단, 방어적으로 null이면 "뉴스:N/A").
- `FundamentalScore`의 5항목 중 relativeStrength가 데이터 부족으로 false인 경우도 그냥
  "강도X"로만 표기(이유까지는 안 남김 — 과한 상세화 방지, §2 범위 밖).
- rationale 문자열이 500바이트를 넘으면 `truncate()`로 잘림 — 정보 손실 가능성은 인지하되,
  현재 설계한 포맷은 실측상 150자 내외로 여유 충분.
- 주문 자체가 실패(Toss API 예외)해도 rationale은 남긴다(왜 "팔려고 했는지"는 유의미한 정보).

## 10. 테스트 계획
- `BuyRationaleTest`/`SellRationaleTest`/`RangeBuyRationaleTest`/`RangeSellRationaleTest`:
  각 조합(저평가 경로/촉매 경로, 하드스탑/트레일스탑/뉴스소멸, 밴드 익절/손절)별 문자열에
  핵심 수치가 포함되는지 검증.
- `OrderExecutorTest`/`RangeOrderExecutorTest`: 기존 테스트의 `buy`/`sell` 호출부에 새
  `rationale` 파라미터 추가(컴파일 유지), `message` 컬럼에 rationale이 반영되는지 1~2건
  검증 케이스 추가.
- `AutoTradeSchedulerTest`/`RangeTradeSchedulerTest`: rationale 조합이 호출되는지(모킹
  검증) 확인.

## 11. 리스크 & 대안 검토
- 대안: 신규 컬럼(`decision_detail` JSON) 추가 — 더 구조화되지만 스키마 변경 필요하고
  이번 요청 긴급도("나중에 파악" 목적) 대비 과함. message 재사용으로 충분하다고 판단
  (되돌리기 쉬운 결정 — 나중에 분석 수요가 커지면 컬럼 분리로 이전 가능, ADR 불필요).
- 리스크: `OrderExecutor.buy`/`sell` 시그니처 변경은 호출부가 2곳(스케줄러, 테스트)뿐이라
  영향범위 작음. 단, 테스트 파일 여러 곳에서 positional 호출 중이라 전부 업데이트 필요
  (이번 세션 #828 때도 동일 패턴 경험함).

## 12. 미해결 질문 (Open Questions)
- 없음. (추후 분석 수요가 늘면 `message` → 구조화 컬럼 분리를 검토할 수 있음 — 지금은
  범위 밖으로 명시적으로 미룸.)
