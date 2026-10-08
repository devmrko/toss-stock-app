# 설계서: NEWS_FADED 매도 제거 (#871)

> **상태**: Approved · **작성**: [AI] Architect · 2026-10-08
> **추적성** — Redmine: #871 · 근거: `docs/reference/investment-principles.md` §4 ·
>   구현: `AutoTradeScheduler` · 테스트: `AutoTradeSchedulerTest`
> **후속**: 2단계(`RISK_EVENT` 논거무효 매도)는 별도 이슈 — #867(타임존) 선행 필요

## 1. 목적 (Why)

`AutoTradeScheduler:124` 가 보유 포지션을 이렇게 판다.

```java
if (exit == ExitReason.NONE && newsFadeDetector.hasNewsFaded(p.getSymbol())
        && !candidateDiscovery.isCheapAndRelativelyStrong(p.getSymbol(), p.getMarket())) {
    exit = ExitReason.NEWS_FADED;
}
```

`hasNewsFaded` 는 **미만료 S4+ 기사가 없으면 true** 다(`NewsFadeDetector:26`).
TTL 은 S4=8시간, S5=24시간(`NewsIngestService.ttl`). 따라서 **S4 기사로 산 포지션은
가격과 무관하게 약 8시간 뒤 강제 매도된다.**

### 1.1 원칙 위반 — 원문 대조

§4 '규칙 요약' 의 매도 규칙은 셋뿐이다.

| 원칙 조항 | 구현 |
|-----------|------|
| **손절(필수 하드룰)**: 진입가 –10% | `HARD_STOP` **있음** |
| **지수 하락기 보정**: 지수 대비 –10% 언더퍼폼 시 교체 **고려** | **없음** |
| **추적 손절**: 최근 고점 –10%(멀티배거 –15~20% 완화 가능) | `TRAIL_STOP` **있음**(완화 조항 없음) |

§4 는 추가로 **"규칙 없는 감정적 익절 금지"** 를 명시한다.
**뉴스 만료를 매도 사유로 삼는 규칙은 원칙에 존재하지 않는다.**

즉 현재 구현은 정확히 거꾸로다 — **원칙의 3번째 규칙은 빠져 있고, 원칙에 없는 규칙이
매도를 지배한다.**

### 1.2 실측 피해

- **2026-10-08 청산 5건 중 4건이 NEWS_FADED** (09:01 `006400`, 09:40 `003670`,
  09:50 `280360`, 11:37 `066570`). 나머지 1건만 `HARD_STOP`(11:12 `053800`).
- **엠플러스(259630) 45회 왕복** — 총손익 −93,140원 중 **수수료·세금이 61,323원**.
  8시간 만료 → 매도 → 신규 기사 유입 → 재매수 루프의 구조적 결과다.

> 기사가 만료된 것은 **그 기사의 수명이 끝난 것**이고, 투자 논거가 무효가 된 것이
> 아니다. 둘을 같은 것으로 취급한 게 설계 오류다.

## 2. 설계 (What)

`AutoTradeScheduler` 의 매도 판정에서 NEWS_FADED 분기를 **제거**한다. 하방은 원칙이
정한 `HARD_STOP`/`TRAIL_STOP` 과 전체 보호장치 `CIRCUIT_BREAKER` 가 지킨다.

```
변경 전: HARD_STOP | TRAIL_STOP | NEWS_FADED | CIRCUIT_BREAKER
변경 후: HARD_STOP | TRAIL_STOP |            | CIRCUIT_BREAKER
```

### 2.1 보존해야 하는 것 (중요)

| 대상 | 조치 | 이유 |
|------|------|------|
| `ExitReason.NEWS_FADED` enum 값 | **제거 금지** | `auto_trade_position.exit_reason` 에 `'NEWS_FADED'` 문자열이 이미 저장돼 있다(오늘 4건 포함). enum 에서 빼면 과거 매매 조회 시 역직렬화가 깨지고 대시보드(`autotrade.html` 총매매 표)가 그 값을 표시한다 |
| `SellRationale` 의 `NEWS_FADED -> "뉴스소멸"` | **유지** | 과거 기록 표시용 |
| `NewsFadeDetector.hasNewsFaded` | **유지** | 아래 §2.2 |
| 후보 풀 경로(`CandidateDiscoveryService:181`, `AutoTradeScheduler:200`) | **유지** | 아래 §2.2 |
| #828/#835 재매수 쿨다운(`newsFadedCooldownMinutes`, `findLastNewsFadedExit`) | **코드 유지** | 과거 EXITED 행에는 계속 작동하고 미래에는 더 쌓이지 않는다. 지금 제거하면 과거 NEWS_FADED 청산분의 재매수 보호가 사라진다 |

### 2.2 기사 만료는 '매수 쪽'만 지배한다 — 분리 원칙

`hasNewsFaded` 자체는 틀린 함수가 아니다. **쓰이는 자리가 틀렸다.**

| 쓰임 | 판단 | 근거 |
|------|------|------|
| 후보 풀에서 빼기(매수 후보 자격 종료) | **타당 — 유지** | 호재가 식은 종목을 계속 "살 만한 후보"로 둘 이유가 없다. 안 사는 방향이라 안전하다 |
| 보유분 매도 | **부당 — 제거** | 원칙 §4 에 없고, 실현손실을 수수료로 확정시킨다 |

비대칭이 의도된 것이다: **기사 만료는 "더 사지 않을 이유"는 되지만 "팔 이유"는 되지
않는다.** 사지 않으면 기회비용이고, 팔면 손실과 수수료가 확정된다.

### 2.3 제거하지 않는 이유가 있는 조건절 — `isCheapAndRelativelyStrong`

현재 조건은 `hasNewsFaded && !isCheapAndRelativelyStrong(...)` 이다. 후자는 #835 에서
"뉴스 식었지만 저평가+상대강세면 팔지 않는다"로 넣은 예외였다. NEWS_FADED 분기를
통째로 제거하면 이 호출도 매도 경로에서 사라진다 — 매수 경로(#828)의 쓰임은 그대로다.

## 3. 함수 등재 (Function Registry)

| 함수 | 파일 | 변경 | I/O |
|------|------|------|-----|
| `AutoTradeScheduler.evaluatePosition`(해당 분기) | `autotrade/AutoTradeScheduler.java` | NEWS_FADED 분기 삭제 | I/O |

신규 함수 없음. 복잡 함수 설계서 불필요(분기 1개 삭제).

## 4. 인수조건 (Acceptance)

1. 보유 포지션이 **기사 만료만으로는 매도되지 않는다**.
2. `HARD_STOP` / `TRAIL_STOP` / `CIRCUIT_BREAKER` 는 그대로 동작한다.
3. `ExitReason.NEWS_FADED` enum 값이 남아 있어 과거 매매 조회·대시보드가 깨지지 않는다.
4. 후보 풀에서 호재 소멸 종목이 빠지는 동작은 유지된다.
5. 매도 판정 경로에서 `newsFadeDetector` 를 더 이상 호출하지 않는다(테스트로 고정).

## 5. 테스트 계획

`AutoTradeSchedulerTest` — 기존에 NEWS_FADED 매도를 검증하던 케이스를 **반대로 고정**한다:

- 뉴스가 식었고(`hasNewsFaded=true`) 저평가·상대강세도 아닌데, 가격이 손절선 위면
  → **매도하지 않는다**(`orderExecutor.sell` 미호출). *기존엔 NEWS_FADED 로 팔았다.*
- 뉴스가 식었어도 `HARD_STOP` 조건이면 → `HARD_STOP` 으로 매도한다(사유가 뉴스소멸이 아님).
- 뉴스가 식었어도 `TRAIL_STOP` 조건이면 → `TRAIL_STOP` 으로 매도한다.
- 매도 판정에서 `newsFadeDetector` 를 호출하지 않는다(인수조건 5).
- 후보 풀 경로는 기존 테스트(`CandidateDiscoveryServiceTest`) 그대로 통과해야 한다.

`SellRationaleTest` 의 NEWS_FADED 문구 테스트는 **유지**(과거 기록 표시용).

## 6. 리스크 / 되돌리기

| 리스크 | 완화 |
|--------|------|
| 논거가 실제로 깨진(수주 취소·유상증자) 포지션을 –10% 까지 들고 간다 | 원칙 §4 가 정한 하방이 바로 –10% 다. 2단계 `RISK_EVENT` 로 좁힌다(별도 이슈, #867 선행) |
| 보유 기간이 길어져 슬롯이 묶인다 | 원칙 §0 은 **핵심 2~5종목·주 1회 점검**을 전제한다. 회전율 하락은 원칙 부합 방향이다 |
| 과거 NEWS_FADED 청산분의 재매수 쿨다운이 의미를 잃는다 | 코드를 남겨 과거 행에는 계속 작동시킨다(§2.1) |

되돌리기: 삭제한 분기 3줄 복원.

## 7. 범위 밖

- **2단계 `RISK_EVENT`**(논거 무효 기반 매도) — #867 타임존 정규화 선행 필요.
  `stock_news.fetched_at`(UTC)과 `auto_trade_position.entry_at`(KST)을 비교해야 하는데
  9시간 어긋난다.
- **원칙 §4-2 지수 열위 교체** — 원칙이 "지수 **하락기**에 ... 교체 **고려**"라고
  조건부·비강제로 쓰여 있다. 이를 자동 하드 매도로 구현하면 **과잉 구현**이다.
  별도 이슈에서 (a) 하락기 판정 기준, (b) 자동 매도 vs 알림 중 무엇으로 할지를
  정해야 한다 — 사용자 판단이 필요한 사안.
- 추적손절 멀티배거 완화(–15~20%) 미구현 — 별도 이슈.
- 분당 매매 vs 원칙 §0/§6 주 1회 주기 정합 — 별도 이슈.
