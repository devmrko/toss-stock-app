# 설계서: 자동매매(#808/#818) 전용 운영 대시보드 페이지 신설 (#848)

> **상태**: Approved
> **작성**: [AI] Architect · **최종수정**: 2026-10-08
> **추적성** — Redmine: #848 · 관련 ADR: 없음
> · 구현 파일: `AutoTradeController`(확장), `RangeTradeController`(신규),
>   `AutoTradeOrderLogMapper`/`.xml`, `RangeTradeOrderLogMapper`/`.xml`(각 `findRecent` 추가),
>   `static/autotrade.html`(신규) ·
>   테스트: `AutoTradeControllerTest`(확장 또는 신규), `RangeTradeControllerTest`(신규)

## 1. 목적 (Why)
`holdings.html`은 수동 거래원장(#430/#433/#441) 전용이고 #808/#818 자동매매 엔진과 연결된
적이 없다. 사용자가 실제 토스 자동매매 거래를 볼 화면이 없어 "홀딩에 토스 거래 안나오는데?"
라고 지적함. 두 엔진이 실제로 뭘 들고 있고 최근에 뭘 사고팔았는지(수수료/세금 포함, #847)
한눈에 보이는 읽기전용 운영 화면이 필요하다.

## 2. 범위 (Scope)
- **포함**: `autotrade.html`(신규) — #808/#818 각각 ① 엔진 상태(dryRun/서킷브레이커/예산),
  ② 보유종목(현재가·가격손익 포함), ③ 최근 주문로그(성공/실패, commission/tax 포함) 표시.
  5초 폴링 자동 갱신(기존 `holdings.html`과 동일한 패턴).
- **제외 (out of scope)**: `holdings.html` 수정 — 그대로 둔다(사용자가 명시적으로 "홀딩 화면은
  수동 원장 그대로 두고 새 화면 추가"를 선택함). 두 원장을 합치는 통합 뷰, 매수/매도 수동 조작
  버튼(이 화면은 읽기전용), #847 수수료 추정치를 보유 중(미확정) 포지션에 선반영하는 것(매도
  전까지는 가격차 손익만 보여주고, 수수료는 "최근 주문로그"에 체결된 것만 표시 — 추측 금지).

## 3. 인수조건 (Acceptance Criteria)
- [ ] `/api/autotrade/status`가 기존 필드(`dryRun`,`circuitBreakerTripped`,`totalBudget`,
      `holdingCount`,`holdings`,`activeCandidates`)를 그대로 유지하면서 `holdingsView`(현재가·
      가격손익·손익률 포함)와 `recentLogs`(최근 20건)를 추가로 반환한다(하위호환 — 기존 키 제거
      없음).
- [ ] `/api/rangetrade/status`(신규)가 동일한 구조(`dryRun`,`circuitBreakerTripped`,
      `totalBudget`,`holdingCount`,`holdingsView`,`recentLogs`)로 응답한다.
- [ ] `autotrade.html`에서 두 엔진의 보유종목·최근주문로그가 실제 DB 데이터와 일치해 보인다
      (현재가는 토스 실시세, commission/tax는 #847 저장값 그대로 — 계산 재수행 안 함).
- [ ] 시세조회 실패 시 해당 종목 현재가만 null로 표시(화면 전체가 깨지지 않음).
- [ ] 기존 `holdings.html`/`/api/holdings` 동작 변경 없음.

## 4. 컨텍스트 & 제약
- 의존성: `PriceCache`(이미 `HoldingService`가 쓰는 배치 시세조회+캐시, N+1/레이트리밋 방지
  패턴 재사용), DB(`auto_trade_position`, `auto_trade_order_log`, `range_trade_position`,
  `range_trade_order_log`).
- 제약: 읽기전용 — 이 화면에서 매수/매도를 트리거하지 않는다(실거래 안전장치 우회 경로를
  만들지 않기 위함). 시세조회 실패가 전체 응답을 막으면 안 됨(`PriceCache.get`은 이미 종목별
  부분실패를 허용 — 기존 `HoldingService`와 동일하게 사용).
- 가정: 이 화면은 운영자(사용자) 본인만 보는 로컬/LAN 전용 화면 — 인증 없음(기존 다른 정적
  페이지와 동일한 신뢰 경계).

## 5. 아키텍처 개요
- 모듈: `AutoTradeController`(확장) · `RangeTradeController`(신규, `AutoTradeController`와
  동일한 "운영 확인용 얇은 어댑터" 성격) · `AutoTradeOrderLogMapper`/`RangeTradeOrderLogMapper`
  (`findRecent` 추가) · `static/autotrade.html`(신규, 순수 JS, 프레임워크 없음 — 기존
  `holdings.html`/`watchlist.html`과 동일 스타일).
- 데이터 흐름:
  ```
  autotrade.html --(fetch, 5s poll)--> GET /api/autotrade/status  --> AutoTradeController
                                                                       ├─ AutoTradeStateMapper.find()
                                                                       ├─ AutoTradePositionMapper.findHolding()
                                                                       ├─ PriceCache.get(symbols)      ← I/O(캐시됨)
                                                                       └─ AutoTradeOrderLogMapper.findRecent(20)
  autotrade.html --(fetch, 5s poll)--> GET /api/rangetrade/status --> RangeTradeController(신규, 위와 동일 구조)
  ```
- I/O ↔ 순수 로직 경계: 보유종목 1건을 현재가와 합쳐 뷰 모델로 바꾸는 계산(가격손익/손익률)은
  순수 함수(`HoldingPnlView.of(...)`, 입력=엔티티+현재가, 출력=뷰 레코드, I/O 없음)로 분리 —
  컨트롤러가 I/O(PriceCache, Mapper)를 수행한 뒤 순수 함수에 결과만 넘긴다.

## 6. 데이터 모델
- **응답(JSON, 두 엔드포인트 동일 구조)**:
  ```json
  {
    "dryRun": true,
    "circuitBreakerTripped": false,
    "totalBudget": 3000000,
    "holdingCount": 5,
    "holdingsView": [
      {"symbol":"086450","market":"KR","entryPrice":21600,"entryQty":27,
       "currentPrice":22800,"priceChangePct":5.55,"unrealizedPnl":32400,
       "dryRun":false,"entryAt":"2026-10-06T13:45:15"}
    ],
    "recentLogs": [
      {"symbol":"066570","side":"BUY","reason":"BUY_SIGNAL","requestedQty":2,
       "requestedPrice":212500,"commission":32,"tax":0,"success":true,
       "dryRun":false,"createdAt":"2026-10-07T11:43:08"}
    ],
    "activeCandidates": []
  }
  ```
- `currentPrice`는 조회 실패 시 `null`(경계 검증: null이면 `priceChangePct`/`unrealizedPnl`도
  `null` — 0으로 단정하지 않음, §9).
- 기존 `holdings`/`activeCandidates` 키는 유지(하위호환, §3).

## 7. 함수 명세 (Function Specs)

| 함수 | 책임(1줄) | 시그니처(잠정) | 입력 | 출력 | 에러/실패 | 복잡? |
|------|-----------|----------------|------|------|-----------|-------|
| `HoldingPnlView.of` | 포지션+현재가 → 가격손익 뷰(순수) | `static HoldingPnlView of(AutoTradePosition p, BigDecimal currentPrice)` | 포지션, 현재가(nullable) | 뷰 레코드 | 현재가 null→손익 필드도 null | 단순 |
| `RangeHoldingPnlView.of` | 동일(레인지 엔진용) | `static RangeHoldingPnlView of(RangeTradePosition p, BigDecimal currentPrice)` | 상동 | 상동 | 상동 | 단순 |
| `AutoTradeController.status`(수정) | 상태+보유+최근로그 조립 | 시그니처 불변(`Map<String,Object>`) | - | 상동 | PriceCache 부분실패 허용 | 단순 |
| `RangeTradeController.status`(신규) | 동일(레인지 엔진) | `Map<String,Object> status()` | - | 상동 | 상동 | 단순 |
| `AutoTradeOrderLogMapper.findRecent` | 최근 N건 주문로그 | `List<AutoTradeOrderLog> findRecent(@Param("limit") int limit)` | limit | 리스트 | 없음(단순 SELECT) | 단순 |
| `RangeTradeOrderLogMapper.findRecent` | 동일 | 상동 | 상동 | 상동 | 상동 | 단순 |

> 전부 "단순"(분기 없음, 외부 I/O는 기존에 검증된 `PriceCache`/Mapper 재사용) — `fn-*.md` 불필요.

## 8. 흐름 / 알고리즘
1. 프론트가 두 엔드포인트를 5초마다 폴링(기존 `holdings.html`과 동일 패턴 — 신규 패턴 아님).
2. 컨트롤러가 보유종목 목록을 가져오고, 심볼 리스트를 모아 `PriceCache.get(symbols)` 1회
   호출(배치, N+1 없음 — `HoldingService`와 동일 원칙).
3. 각 보유종목을 현재가와 짝지어 `HoldingPnlView.of`로 변환(순수, 테스트 가능).
4. `findRecent(20)`으로 최근 주문로그를 가져와 그대로 직렬화(가공 없음 — commission/tax는
   #847이 이미 계산해 저장한 값을 그대로 보여줄 뿐).
5. 프론트는 두 섹션(모멘텀/레인지)에 테이블로 렌더링, dry-run 포지션은 배지로 구분 표시.

## 9. 엣지케이스 & 에러 처리
- 시세조회 전체 실패(Toss API 다운) → `PriceCache.get`이 빈 리스트 반환해도 `holdingsView`는
  `currentPrice=null`인 행으로 채워짐(화면이 비거나 500 에러를 내지 않음) — 기존
  `HoldingService`가 이미 이 경로를 그렇게 처리 중(동일 패턴 재사용, 신규 리스크 없음).
- 보유종목 0개 → 빈 배열(정상 상태, 에러 아님).
- `recentLogs`의 commission/tax가 `null`(#847 배포 전 과거 로그, 또는 드라이런 예산초과 등
  조기반환 경로) → 화면엔 "-"로 표시, 0으로 단정하지 않음(수수료 모름 ≠ 수수료 0).

## 10. 테스트 계획
- `HoldingPnlViewTest`: 현재가 있음/없음(null)/entry와 동일(0% 변동) 3케이스.
- `AutoTradeControllerTest`(확장): `status()` 응답에 `holdingsView`/`recentLogs` 키 존재 및
  값 검증(Mockito로 PriceCache/Mapper 스텁).
- `RangeTradeControllerTest`(신규): 동일 구조 검증.
- 수동: `pm2 restart` 후 `autotrade.html` 브라우저로 열어 실제 보유종목 5건(086450/003670/
  053800/006400/066570) + 최근 로그가 보이는지 눈으로 확인.

## 11. 리스크 & 대안 검토
- 대안(기각): `holdings.html`에 자동매매 데이터를 끼워넣기 — 사용자가 명시적으로 "홀딩 화면은
  그대로, 새 화면 추가"를 선택(수동 원장과 자동매매는 성격이 달라 한 화면에 억지로 합치면
  필터/표시 로직이 복잡해짐, §2).
- 대안(기각): React/Vue 등 프레임워크 도입 — 기존 정적 페이지들이 전부 순수 JS라 일관성 유지,
  이 화면도 읽기전용 테이블이라 프레임워크가 필요한 복잡도가 아님.
- 리스크: 낮음 — 읽기전용, 기존에 검증된 `PriceCache`/Mapper 패턴 재사용, 기존 엔드포인트는
  키 추가만(하위호환).

## 12. 미해결 질문 (Open Questions)
- (보류) 보유 포지션의 "수수료 반영 추정 손익"을 매도 전에도 미리 보여줄지 — 현재는 매도
  시점에만 실제 commission/tax가 로그에 찍히므로, 보유 중엔 가격차 손익만 표시(§2 제외 사유).
  필요해지면 별도 이슈로 분리(`TradingFeeCalculator.commission/tax(현재가치, market, "SELL")`을
  참고용 "매도 시 예상 비용" 컬럼으로 추가하는 정도로 확장 가능).
