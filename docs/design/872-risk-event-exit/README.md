# 설계서: RISK_EVENT 매도 — 논거 무효 기반 (#872)

> **상태**: Approved · **작성**: [AI] Architect · 2026-10-08
> **추적성** — Redmine: #872(#871 2단계, #867 선행 완료) ·
>   근거: `docs/reference/investment-principles.md` §3-3, §3-5 ·
>   구현: `ExitReason`, `RiskEventDetector`(신규), `RiskVerdict`(신규),
>   `AutoTradeScheduler`, `SellRationale`, `StockNewsMapper`(+XML) ·
>   테스트: `RiskEventDetectorTest`(신규), `AutoTradeSchedulerTest`, `SellRationaleTest`

## 1. 목적 (Why)

#871 에서 NEWS_FADED(기사 **만료** 기반) 매도를 제거했다. 하방은 원칙 §4 의
`HARD_STOP`/`TRAIL_STOP` 이 지킨다.

다만 논거가 **실제로 깨진** 경우 — 유상증자 결정, 횡령·배임, 상장폐지·거래정지,
블록딜, 소송·제재, 적자 전환 — 에는 −10% 까지 끌고 가는 것보다 즉시 이탈하는 게 낫다.
원칙 §3-3(유증·회사채 남발 없음)과 §3-5(분쟁·규제·소송 Low)는 **보유 중에도 깨지면
안 되는 조건**이다.

> **기사 만료 ≠ 논거 무효.** 전자는 그 기사의 수명이 끝난 것이고, 후자는 살 이유가
> 사라진 것이다. #871 이 전자를 제거했고, 이 이슈가 후자를 추가한다.

#867(타임존 정규화)이 선행조건이었고 완료됐다 — 이제 `stock_news` 와
`auto_trade_position` 의 시각을 같은 기준(KST)으로 비교할 수 있다.

## 2. 설계 (What)

새 `ExitReason.RISK_EVENT` 를 추가하고, 보유 포지션마다 **진입 이후에 새로 나온**
리스크 기사를 찾는다.

### 2.1 판정 재료 — 이미 만든 것을 재사용한다

| 재료 | 출처 | 판정 |
|------|------|------|
| `NewsFacts.riskFlag` ≠ `NONE` | #865 | `DILUTION`·`GOVERNANCE`·`DELISTING`·`BLOCKDEAL`·`LITIGATION`·`LOSS` |
| `TitleGuard.lossSide(title)` | #869 | 적자·영업손실·순손실·어닝쇼크 (결정론적) |

둘 중 **하나라도** 걸리면 매도한다. LLM 이 `riskFlag` 를 놓쳐도 제목 가드가 잡는
**이중 구조**다 — 실제로 `갤Z 폴드8 흥행에도…DX부문, 2조 적자` 가 `riskFlag=NONE`
으로 온 사례가 있었다(#869 §1).

`priceAlreadyMoved`·`beneficiary` 는 보지 않는다 — 매수 자격 판정용이고, 보유분을
팔 이유가 아니다.

### 2.2 조회 구간 — `MAX(entry_at, now − 24h)` ~ `now`

**왜 진입 이후만 보는가**: 진입 전에 이미 있던 리스크 기사로 즉시 매도하면 매수·매도가
한 틱에 왕복한다. 애초에 #865 게이트가 `riskFlag≠NONE` 기사를 후보 등록에서 차단하므로
그런 종목은 사지 않는다.

**왜 24시간으로 바운드하는가**: 진입 이후 전체를 매 틱 스캔하면 장기 보유 포지션에서
`stock_news`(61,521행)에 대한 `targets LIKE` 스캔이 **포지션 수 × 틱 주기**마다 발생한다
— 여러 프로젝트가 공유하는 20GB DB 에 과한 부하다. 틱이 분 단위로 돌므로 **리스크 기사는
등장 후 24시간 안에 반드시 한 번은 스캔된다**. 한 번 탐지되면 즉시 매도하므로 과거분을
다시 볼 필요가 없다.

> **한계**: 앱이 24시간 이상 정지하면 그 사이의 기사를 놓칠 수 있다. 그 경우에도
> `HARD_STOP`/`TRAIL_STOP` 은 복구 후 정상 작동한다.

만료 여부는 **보지 않는다**(`expires_at` 무관) — 리스크 사건은 기사가 만료돼도
유효하다. 기존 `forSymbolBetween` 이 정확히 이 형태다(만료 무관, 구간 조회).

### 2.3 매도 판정 순서

```
1. TrailingStopCalculator.decide → HARD_STOP | TRAIL_STOP | NONE
2. NONE 이면 RiskEventDetector.detect → RISK_EVENT
3. (서킷브레이커는 별도 경로)
```

손절이 먼저다 — 원칙 §4 의 **필수 하드룰**이고, 이미 −10% 를 깨뜨린 포지션은 리스크
기사 유무와 무관하게 팔아야 한다. 사유 문자열도 그쪽이 더 정확하다.

### 2.4 매도 사유 문자열

`SellRationale.describe` 에 **선택적 비고(note)** 를 받는 오버로드를 추가한다.
기존 6-인자 버전은 `note=null` 로 위임한다(기존 호출부·테스트 불변).

```
리스크이벤트(피크235000→현재225000,-4.26%) 진입200000 수익+12.5%
  스탑211500(하드180000/트레일211500) 사유:DILUTION "OO전자, 1200억 유상증자 결정"
```

`ExitReason` 의 `switch` 는 default 없이 전수 분기라, enum 에 값을 더하면
**컴파일러가 누락을 잡는다** — `label()` 에 `RISK_EVENT -> "리스크이벤트"` 추가.

### 2.5 스키마

`exit_reason VARCHAR2(30)` 이므로 `'RISK_EVENT'`(10자)가 들어간다. **DDL 변경 없음.**

## 3. 함수 등재 (Function Registry)

| 함수 | 파일 | 책임 | I/O | 비고 |
|------|------|------|-----|------|
| `record RiskVerdict(String flag, String title)` | `autotrade/RiskVerdict.java` (신규) | 탐지된 리스크의 종류와 근거 기사 | 순수 | |
| `RiskEventDetector.detect(String, LocalDateTime)` | `autotrade/RiskEventDetector.java` (신규) | 진입 이후 리스크 기사 탐색 | I/O(뉴스 조회) | 없으면 null |
| `RiskEventDetector.judge(String title, String factsJson)` | 동일 | 기사 1건의 리스크 판정 | **순수** | 테스트 본체 |
| `RiskEventDetector.scanFrom(LocalDateTime entryAt, LocalDateTime now)` | 동일 | §2.2 조회 하한 계산 | **순수** | |
| `SellRationale.describe(..., String note)` | 기존 수정 | 비고 추가 오버로드 | 순수 | 6-인자 버전은 위임 |
| `SellRationale.label` | 기존 수정 | `RISK_EVENT` 분기 추가 | 순수 | |
| `StockNewsMapper.forSymbolBetween` | 기존 수정 | SELECT 목록에 `facts` 추가 | I/O | 조건 불변 |
| `AutoTradeScheduler.processHolding` | 기존 수정 | §2.3 판정 2단계 추가 | I/O | |

판정 본체를 순수 함수 `judge`/`scanFrom` 으로 분리해 I/O 없이 테스트한다(CLAUDE.md §1).
분기가 단순하므로 별도 함수 설계서는 두지 않는다 — 규칙이 §2.1·§2.2 표에 등재돼 있다.

## 4. 인수조건 (Acceptance)

1. 진입 이후 `riskFlag≠NONE` 기사가 나오면 `RISK_EVENT` 로 매도된다.
2. LLM 이 `riskFlag=NONE` 으로 줬어도 제목이 적자류면 매도된다.
3. 진입 **이전** 기사만 있으면 매도되지 않는다.
4. 리스크 기사가 없으면 매도되지 않는다(**#871 회귀 방지**).
5. `HARD_STOP`/`TRAIL_STOP` 이 동시 조건이면 **그쪽이 우선**한다.
6. 매도 사유 문자열에 리스크 종류와 기사 제목이 남는다.
7. `exit_reason='RISK_EVENT'` 가 저장되고 대시보드에서 조회된다.
8. 조회 하한이 `MAX(entry_at, now−24h)` 다(장기 보유 포지션의 전체 스캔 방지).

## 5. 테스트 계획

`RiskEventDetectorTest` — 순수 함수 위주:

- `judge`: `riskFlag=DILUTION` facts + 중립 제목 → `DILUTION`
- `judge`: `riskFlag=NONE` facts + `"…DX부문, 2조 적자"` → `LOSS` (**인수조건 2**)
- `judge`: facts null + 적자 제목 → `LOSS` (facts 없어도 제목 가드는 동작)
- `judge`: `riskFlag=NONE` + 정상 제목 → null
- `judge`: `riskFlag=NONE` + `"흑자전환 성공"` → null (가드 제외 목록)
- `scanFrom`: `entry_at` 이 24시간 이내면 `entry_at` 반환
- `scanFrom`: `entry_at` 이 24시간보다 오래됐으면 `now−24h` 반환 (**인수조건 8**)
- `scanFrom`: `entry_at` null → `now−24h` (방어)
- `detect`: 리스크 기사 있음 → `RiskVerdict` 반환, 조회 `from` 이 `scanFrom` 값
- `detect`: 기사 없음 → null

`AutoTradeSchedulerTest`:
- 리스크 기사 있고 가격은 손절선 위 → `RISK_EVENT` 로 매도 (**인수조건 1**)
- 리스크 기사 없음 → 미매도 (**인수조건 4**, #871 회귀 방지)
- 리스크 기사 있고 하드손절 조건도 충족 → `HARD_STOP` (**인수조건 5**)
- 사유 문자열에 flag·제목 포함 (**인수조건 6**)

`SellRationaleTest`: `RISK_EVENT` 라벨 + note 포맷, note=null 이면 기존 출력과 동일.

## 6. 리스크 / 되돌리기

| 리스크 | 완화 |
|--------|------|
| 오탐으로 멀쩡한 포지션을 팔아 손실·수수료 확정 | 재료가 둘 다 보수적이다. `riskFlag` 는 #865 에서 라이브 321건 중 1.6%만 발화했고, `TitleGuard` 는 과차단 0건으로 검증됐다(#869). 배포 후 발생 건을 개별 확인한다 |
| `LOSS` 가 과하게 넓다(적자 언급 기사 전부) | `TitleGuard` 의 제외 목록(흑자전환·적자 축소·적자 탈출)이 이미 있고 테스트로 고정돼 있다 |
| 24시간 바운드로 장기 정지 중 기사를 놓침 | §2.2 에 한계 명시. 하드·추적 손절은 복구 후 정상 작동 |
| 틱마다 포지션 수만큼 뉴스 조회 | 구간이 최대 24시간으로 바운드. 기존 `active()` 조회도 같은 빈도로 돌고 있었다 |

되돌리기: `processHolding` 의 2단계 분기 제거(§2.3). enum·detector 는 남겨도 무해.

## 7. 범위 밖

- **원칙 §4-2 지수 열위 교체** — "지수 **하락기**에 교체 **고려**"라 조건부·비강제.
  자동 하드 매도로 구현하면 과잉. 하락기 기준과 자동매도 vs 알림 선택에 사용자 판단 필요.
- 추적손절 멀티배거 완화(−15~20%).
- 분당 매매 vs 원칙 §0/§6 주 1회 주기 정합.
- 리스크 기사 탐지 시 해당 종목의 **재매수 차단**(쿨다운) — 현재 #865 게이트가 리스크
  기사를 후보 등록에서 막으므로 자연히 재매수되지 않는다. 별도 조치 불필요로 판단.
