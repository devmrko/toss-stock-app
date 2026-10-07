# 설계서: 대시보드 — 매수근거/주문상세/요약통계/페이지네이션 (#849)

> **상태**: Approved
> **작성**: [AI] Architect · **최종수정**: 2026-10-08
> **추적성** — Redmine: #849(기반 #848) · 관련 ADR: 없음
> · 구현 파일: `AutoTradeController`/`RangeTradeController`(확장), `OrderExecutor`/
>   `RangeOrderExecutor`(entry_price 저장), `AutoTradeOrderLogMapper`/`RangeTradeOrderLogMapper`
>   /`.xml`(페이지네이션+집계), `AutoTradePositionMapper`/`RangeTradePositionMapper`/`.xml`
>   (승/패 집계), `static/autotrade.html` ·
>   테스트: 기존 컨트롤러 테스트 확장 + 신규 매퍼 쿼리 검증은 수동(실DB)으로 대체(단순 SELECT)

## 1. 목적 (Why)
#848로 보유종목/최근로그가 보이게 됐지만, 사용자 피드백: "코드로 산 이유를 모른다",
"주문에 진입가/판매가가 안 보인다", "전체 매매 흐름(손익·건수)을 한눈에 볼 수 없다",
"최근 20건만 봐서 과거 매매를 볼 수 없다". 이번 이슈는 그 4가지를 해결한다.

## 2. 범위 (Scope)
- **포함**:
  1. 모멘텀(#808) 보유종목에 `buyReason`(매수 당시 결정근거 전문, #832 rationale — 호재
     문구 포함) 추가.
  2. 양쪽 엔진 주문로그에 `entryPrice`(진입가) 저장/표시 추가 — BUY행은 체결가 자체,
     SELL행은 그 포지션의 매수가(이미 알고 있던 값, 재조회 없음). 기존 `requestedPrice`는
     그대로 "체결가"(BUY=매수가, SELL=판매가)로 명칭만 명확히.
  3. 엔진별 요약(총 매매건수, 승/패 건수, 누적 실현손익, 누적 수수료+세금) — `/status`에
     `summary` 키 추가.
  4. 주문로그를 고정 20건 → 페이지네이션(시간순, 최신→과거, `page`/`size`)으로 전체 이력
     조회 가능하게 변경. 신규 엔드포인트 `GET /api/{autotrade|rangetrade}/orderlog?page=N&size=20`.
- **제외 (out of scope)**: 레인지(#818) 보유종목에 매수이유 추가(사용자가 모멘텀만 명시),
  주문로그의 `message`/`reason` 재가공(이미 #832로 rationale이 message에 있음 — 그대로
  노출), 승률 외 고급 통계(샤프비율 등).

## 3. 인수조건 (Acceptance Criteria)
- [ ] 모멘텀 보유종목 각 행에 매수 당시 rationale 전문이 보인다(새로 산 포지션은 즉시,
      기존 보유분도 과거 주문로그에서 역추적해 보인다).
- [ ] 주문로그 각 행에 진입가+체결가(판매가)+수수료+세금+이유(message)가 모두 보인다.
- [ ] 각 엔진 섹션 상단에 요약(총 매매 N건, 승 N/패 N, 누적 실현손익 ±N원, 누적 수수료
      N원)이 보인다.
- [ ] 주문로그가 페이지 단위(기본 20건/페이지)로 전체 이력을 시간순(최신→과거)으로
      넘겨볼 수 있다(이전/다음 버튼, 전체 건수 표시).
- [ ] 기존 `/status` 응답의 `holdings`/`activeCandidates`/`holdingsView` 키는 유지(하위호환).

## 4. 컨텍스트 & 제약
- 의존성: 기존 `AutoTradeOrderLogMapper`/`AutoTradePositionMapper`(및 레인지 대응), Oracle
  `OFFSET ... FETCH NEXT ... ROWS ONLY` 페이지네이션 문법(이미 `FETCH FIRST N ROWS ONLY`
  패턴을 쓰고 있어 동일 계열).
- 제약: 매수이유 역추적은 "해당 심볼의 가장 최근 성공 BUY 로그"로 찾는다 — 동일 심볼을
  중복보유할 수 없다는 기존 불변식(§9) 덕분에, 현재 HOLDING인 포지션에 대해 이 최신 BUY
  로그는 항상 그 포지션 자신의 매수 기록과 일치한다(동시에 2개 보유 불가).
- 가정: `entry_price`는 BUY 행에서는 사실상 `requested_price`와 같은 값이지만, 컬럼으로
  명시 저장해두면 프론트가 BUY/SELL 구분 없이 같은 컬럼을 그대로 렌더링할 수 있어 단순함.

## 5. 아키텍처 개요
```
GET /api/autotrade/status
  ├─ holdingsView: [...HoldingPnlView(symbol,name,...,buyReason)]   ← 추가: buyReason
  ├─ summary: {totalTrades, wins, losses, realizedPnl, totalFees}    ← 신규
  └─ (recentLogs 제거 — orderlog 엔드포인트로 이동)

GET /api/autotrade/orderlog?page=0&size=20
  └─ {content:[...AutoTradeOrderLog(entryPrice 포함)], page, size, totalElements}

GET /api/rangetrade/status, GET /api/rangetrade/orderlog  — 동일 구조(buyReason만 제외)
```
I/O ↔ 순수 로직: 페이지 응답 조립(`OrderLogPage` record 생성)은 순수, DB 페이징 쿼리 자체는
I/O(매퍼).

## 6. 데이터 모델
- `auto_trade_order_log`/`range_trade_order_log`: `entry_price NUMBER`(신규, nullable) 추가.
- 응답 `summary`: `{"totalTrades":12,"wins":7,"losses":5,"realizedPnl":-24950,"totalFees":3200}`
  (전부 EXITED 포지션/성공 주문로그 기준 — DRY-RUN 포함, 실거래만 분리 집계는 범위 밖).
- 응답 `orderlog`: `{"content":[...],"page":0,"size":20,"totalElements":87}`.

## 7. 함수 명세 (Function Specs)

| 함수 | 책임 | 시그니처(잠정) | 복잡? |
|------|------|----------------|-------|
| `AutoTradeOrderLogMapper.findPage` | 시간순 페이지 조회 | `List<AutoTradeOrderLog> findPage(@Param("offset") int offset, @Param("limit") int limit)` | 단순 |
| `AutoTradeOrderLogMapper.countAll` | 전체 건수 | `int countAll()` | 단순 |
| `AutoTradeOrderLogMapper.totalFees` | 성공 주문 수수료+세금 합 | `BigDecimal totalFees()` | 단순 |
| `AutoTradeOrderLogMapper.findLastBuySuccess` | 심볼의 최근 성공 BUY 로그(매수이유 역추적) | `AutoTradeOrderLog findLastBuySuccess(@Param("symbol") String symbol)` | 단순 |
| `AutoTradePositionMapper.countExited/countWin/countLoss` | 매매건수/승/패 집계 | `int countExited()` 등 | 단순 |
| `RangeTradeOrderLogMapper.findPage/countAll/totalFees` | 동일(레인지) | 상동 | 단순 |
| `RangeTradePositionMapper.countExited/countWin/countLoss` | 동일(레인지) | 상동 | 단순 |
| `OrderExecutor.buy/sell`(수정) | entryPrice도 함께 저장 | 시그니처 불변(내부 `saveLogSafely` 파라미터만 추가) | 단순 |

> 전부 "단순"(분기 없음, 기존 패턴 그대로 재사용) — `fn-*.md` 불필요.

## 8. 흐름 / 알고리즘
1. 매수이유: 컨트롤러가 보유종목 symbol마다 `findLastBuySuccess(symbol)`를 호출해 그
   로그의 `message`(rationale 전문)를 `HoldingPnlView.buyReason`에 담는다.
2. 진입가: `OrderExecutor.buy`에서 `entryPrice=filledPrice`를, `sell`에서
   `entryPrice=position.getEntryPrice()`를 각각 `saveLogSafely`에 추가 전달 → 컬럼 저장.
3. 요약: `countExited/countWin/countLoss/realizedPnlTotal/totalFees`를 한 번씩 호출해
   `summary` 맵으로 조립.
4. 페이지네이션: 프론트가 `?page=N`을 바꿔가며 `/orderlog`를 호출, `totalElements/size`로
   페이지 수를 계산해 이전/다음 버튼을 활성/비활성.

## 9. 엣지케이스 & 에러 처리
- `findLastBuySuccess`가 못 찾으면(과거 데이터 누락 등) `buyReason=null` — 화면엔 "-"
  표시(추측 금지).
- 승/패 외 "무승부"(exit_price == entry_price)는 `totalTrades`에는 포함되지만 승/패
  어느 쪽에도 안 들어간다(엣지케이스, §12).
- 페이지 파라미터 범위 밖(음수, totalElements 초과) → 빈 리스트 반환(에러 대신).

## 10. 테스트 계획
- `AutoTradeControllerTest`/`RangeTradeControllerTest`: `summary` 키 값 검증(Mockito 스텁).
- 신규 `AutoTradeOrderLogControllerTest`(or 기존 컨트롤러에 orderlog 메서드 추가 시 동일
  클래스): 페이지네이션 응답 형태 검증.
- 수동: 배포 후 실DB로 페이지 넘기기/요약 숫자가 실제 holdings.html·Toss 앱 수치와
  방향이 맞는지 확인.

## 11. 리스크 & 대안 검토
- 대안(기각): 매수이유를 포지션 테이블에 비정규화 저장 — 스키마 변경 범위가 커지고
  기존 `auto_trade_position`을 건드려야 함. 대신 "현재 HOLDING은 최신 BUY 로그와 1:1"이라는
  불변식을 활용해 역추적 조회로 해결(스키마 변경 없음, §4).
- 리스크: 낮음 — 읽기전용 조회 추가, 기존 저장 경로에 컬럼 1개만 추가.

## 12. 미해결 질문 (Open Questions)
- 무승부(본전) 거래를 승/패 어느 쪽에도 안 넣는 현재 방식이 맞는지 — 필요시 "무승부"
  카운트를 별도로 노출하는 걸로 확장 가능.
