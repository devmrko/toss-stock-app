# 설계서: 매수 스캔 탈락 사유 계측 (#884)

> **상태**: Approved · **작성**: [AI] Architect · 2026-10-09
> **추적성** — Redmine: #884 · 구현: `ScanVerdict`(신규), `AutoTradeScheduler`,
>   `AutoTradeController` · 테스트: `AutoTradeSchedulerTest`, `AutoTradeControllerTest`

## 1. 목적 (Why)

`AutoTradeScheduler.scanCandidates` 의 탈락 지점이 **12개인데 로그를 남기는 곳이
하나도 없다.** `tick()` 도 조기 반환 경로(상태없음·서킷브레이커·슬롯없음)가 무기록이다.

그래서 2026-10-08~09 작업에서 **"TSM 이 어디서 막혔나", "US 가 왜 한 번도 매수하지
않았나"를 전부 추측으로만 답했다.** 계측이 없으면 모든 게이트 질문이 외부 지수 프록시
추정으로 되돌아간다.

그리고 이것은 **게이트 튜닝의 선행조건**이다. 하루에 게이트를 네 번 고쳤는데
(#879 → #881 → #883) 전부 외부 지수 데이터로만 검증했다. 봇 자신의 판정으로 검증해야
하고, 그러려면 판정이 기록돼야 한다.

> 참고: 현재 `auto_trade_position` EXITED 54건 중 **51건이 `NEWS_FADED`**(#871 로
> 제거된 규칙)라 현 로직 평가에 쓸 수 없다. 지금부터 쌓아야 한다.

## 2. 설계 (What)

**매매 동작은 바꾸지 않는다. 순수 계측 추가다.**

### 2.1 기록 단위

```java
record ScanVerdict(String symbol, String market, String stage, String detail)
```

- `stage` — 어느 게이트에서 끝났는지(짧은 식별자). 집계·그래프용.
- `detail` — 사람이 읽을 근거(수치 포함). 사후 판단용.
- 매수 성공도 `stage="BUY"` 로 기록한다 — "왜 샀나"도 같은 자리에서 보여야 한다.

### 2.2 스냅샷은 최신 1틱만 보관한다

틱마다 교체한다(누적 아님). 이유:

- 누적하면 메모리가 무한히 자란다(분당 틱 × 후보수).
- "지금 왜 안 사는가"가 질문이므로 **최신 상태**가 답이다.
- 이력이 필요하면 DB 테이블을 만들어야 하는데, 그건 쓰임이 확인된 뒤에 할 일이다
  (현재는 질문에 답할 수 있는지가 먼저).

### 2.3 로그는 틱당 요약 1줄

12개 지점 × 후보수를 전부 INFO 로 쓰면 로그가 폭주한다. 스냅샷을 보관하고
**stage 별 건수만** 한 줄로 남긴다.

```
매수 스캔: 후보 1건 → BUY 0, POPULARITY 1   (슬롯 2)
```

### 2.4 노출

`GET /api/autotrade/status` 에 `scan` 필드로 싣는다 — 이미 대시보드가 이 엔드포인트를
쓰고 있어 추가 배선이 없다.

### 2.5 tick 조기 반환도 기록한다

`scanCandidates` 에 도달하지 못하는 경우가 "왜 안 샀나"의 답인 경우가 많다.

| 경로 | stage |
|------|-------|
| `auto_trade_state` 없음 | `NO_STATE` |
| 서킷브레이커 발동 | `CIRCUIT_BREAKER` |
| 빈 슬롯 없음 | `NO_SLOT` |
| 후보 자체가 0건 | `NO_CANDIDATE` |

이 경우 `symbol`/`market` 은 null 이고 `detail` 에 수치를 담는다.

## 3. 함수 등재 (Function Registry)

| 함수 | 파일 | 책임 | I/O |
|------|------|------|-----|
| `record ScanVerdict(String, String, String, String)` | `autotrade/ScanVerdict.java` (신규) | 판정 1건 | 순수 |
| `AutoTradeScheduler.lastScan()` | 신규 | 최신 스냅샷 조회 | 순수(volatile 읽기) |
| `AutoTradeScheduler.lastScanAt()` | 신규 | 스냅샷 시각 | 순수 |
| `AutoTradeScheduler.scanCandidates` | 기존 수정 | 탈락 지점마다 기록 | I/O |
| `AutoTradeScheduler.tick` | 기존 수정 | 조기 반환 사유 기록 | I/O |
| `AutoTradeController.status` | 기존 수정 | `scan` 필드 추가 | I/O |

## 4. 인수조건 (Acceptance)

1. 후보가 탈락하면 그 `stage` 와 `detail` 이 스냅샷에 남는다.
2. 매수 성공도 스냅샷에 남는다(`stage="BUY"`).
3. `tick` 조기 반환 사유도 남는다(`NO_SLOT` 등).
4. 틱마다 스냅샷이 **교체**된다(누적 아님).
5. 로그는 틱당 **요약 1줄**.
6. `status` 응답으로 조회된다.
7. **매매 판정 결과는 변경되지 않는다**(기존 테스트 전부 통과).

## 5. 테스트 계획

`AutoTradeSchedulerTest`:
- 인기 미달로 탈락 → 스냅샷에 `stage=POPULARITY`, symbol 포함 (인수조건 1)
- 시장 마감 → `stage=MARKET_CLOSED`
- 매수 성공 → `stage=BUY` (인수조건 2)
- 슬롯 0 → `stage=NO_SLOT`, scanCandidates 미진입 (인수조건 3)
- 후보 0건 → `stage=NO_CANDIDATE`
- 두 번 tick → 스냅샷이 누적되지 않고 교체 (인수조건 4)
- 기존 매수/매도 테스트 전부 통과 (인수조건 7)

`AutoTradeControllerTest`: `status()` 에 `scan` 키가 포함된다 (인수조건 6)

## 6. 리스크 / 되돌리기

| 리스크 | 완화 |
|--------|------|
| 계측 코드가 매매 흐름을 바꿈 | 기록은 리스트 add 뿐이고 분기 조건을 건드리지 않는다. 기존 테스트로 고정 |
| 메모리 증가 | 최신 1틱만 보관(§2.2). 후보는 최대 수십 건 |
| 로그 폭주 | 틱당 1줄 요약(§2.3) |

되돌리기: `status` 의 `scan` 필드와 기록 호출 제거.

## 7. 범위 밖

- **판정 이력 DB 적재** — §2.2 의 이유로 쓰임이 확인된 뒤에. 지금은 "질문에 답할 수
  있는가"가 먼저다.
- 매도 측 판정 계측 — 매도는 이미 `SellRationale` 로 사유가 주문로그에 남는다.
