# 설계서: 세션 종료 전 일봉 저장 차단 (#885)

> **상태**: Draft
> **작성**: [AI] Architect · **최종수정**: 2026-10-09
> **추적성** — Redmine: #885 · 관련 ADR: 없음
> · 구현 파일: `market/BarCompleteness.java`, `market/DailyCollector.java`
> · 테스트: `market/BarCompletenessTest.java`, `market/DailyCollectorTest.java`

## 1. 목적 (Why)

> US 일봉 최신 바가 항상 **미완성(프리마켓 몇 분)** 이어서 인기 게이트·급등률 판정이
> 잘못된 숫자를 쓰고 있다 — US 매수가 구조적으로 전면 차단됐다.

원래 #885 는 "US 거래량이 깨졌다"로 등록됐다. 조사 결과 **데이터 소스 문제가 아니었다.**

### 진단 (실측)

`scheduledRefresh` 는 `0 40 15 * * MON-FRI`(KST 15:40)에 돈다. KR 장 마감(15:30) 직후라
KR 에는 정확하다. 그런데 **15:40 KST = 미국 동부 02:40, 그날(ET) 프리마켓**이다.
Toss 가 "오늘(ET)" 캔들을 주면 프리마켓 몇 분만 담긴 바가 그 ET 날짜로 저장된다.

`getDailyCandles(symbol, 2)` 로 최근 2개를 받으므로 **다음 날 실행 때 전날 바는 완성값으로
덮어써진다** → 과거 바는 정확하고 **최신 바 1개만 항상 쓰레기**다.

| 검증 항목 | 값 |
|---|---|
| US 최신 바 거래량 / 20일평균 **중위** | **0.1252** (60종목 중 41종목이 0.3 미만) |
| US **전일** 바 거래량 / 20일평균 중위 | **0.9601** (완성 바는 정상) |

종목별 대조(DB vs 야후):

| | DB 10-08 | 야후 10-08 | DB 10-07 이전 |
|---|---|---|---|
| INTC 종가 | 113.55 | **107.08** | 야후와 완전 일치 |
| INTC 거래량 | **88,533** | 119,637,300 | 일치 |
| TSM 종가 | 465.57 | **457.99** | 완전 일치 |
| TSM 거래량 | **83,712** | 13,400,300 | 일치 |

그 최신 바가 바로 `PopularityChecker`(거래량 스파이크·당일 가격반응)와
`PriceExtension`(최근 저점 대비)이 쓰는 바다. 그래서 US 주문로그가 **0행**이었다.

## 2. 범위 (Scope)

- **포함**: 세션이 끝나지 않은 거래일의 일봉을 **저장하지 않는다**(KR·US 공통).
- **제외**
  - 수집 스케줄 변경 — 가드가 모든 경로(정기·후방백필·종목백필)를 덮으므로 불필요하다.
    US 전용 스케줄을 추가하면 경로가 둘로 갈라져 같은 버그가 재발할 여지가 생긴다.
  - 야후로 US 일봉 소스 교체 — 완성 바는 이미 정확하다(중위 0.9601). 소스를 바꿀 이유가 없다.
  - 서머타임 정밀 처리 — §4 의 보수적 임계로 대신한다.
  - US 매수 활성화 — #886(환율) 선행 + `screener-markets` 변경이 필요하다. 별도.

## 3. 인수조건 (Acceptance Criteria)

- [ ] **AC1** 세션이 끝나지 않은 거래일의 바는 `upsert` 되지 않는다(3개 저장 경로 전부).
- [ ] **AC2** 세션이 끝난 거래일의 바는 기존대로 저장된다 — KR 15:40 정기 수집 회귀 없음.
- [ ] **AC3** 과거 바(후방 백필·종목 백필)는 영향받지 않는다.
- [ ] **AC4** 가드 적용 후 US 최신 바 거래량/20일평균 중위가 0.3 이상으로 회복된다.
- [ ] **AC5** 판정 불가(거래일 null)는 저장하지 않는다(fail-closed).

## 4. 컨텍스트 & 제약

- **저장 관문이 하나다**: `DailyCollector.toDaily(symbol, candle)` — 3개 `upsert` 호출
  (`backfillSymbol:124`, `runScan:154`, 그리고 `runScan` 을 쓰는 정기/후방백필)이 모두
  이 메서드를 거치고, **null 을 반환하면 저장을 건너뛴다**. 가드를 여기 한 곳에 넣는다.
- **거래일의 의미**: `tradeDate()` 는 `OffsetDateTime.parse(c.timestamp()).toLocalDate()` 다.
  US 캔들의 오프셋은 ET 이므로 결과는 **ET 날짜**다 — US 바의 거래일로 올바르다.
- **서머타임**: 16:00 ET 마감의 KST 환산은 **05:00(여름)~06:00(겨울)** 로 변한다.
  `MarketHours` 는 US 를 22:30~05:00 KST 고정으로 쓰는 기존 단순화를 갖고 있다.
  여기서는 그 단순화를 따르지 않고 **D+1 07:00 KST** 를 임계로 쓴다 — 두 경우를 여유 있게
  덮고, "늦게 인정"하는 방향의 오차만 낸다(미완성 바를 받아들이는 오차는 내지 않는다).
- **패키지 레이어**: `MarketHours` 는 `autotrade` 패키지다. `market` 패키지가 `autotrade` 를
  의존하면 방향이 뒤집힌다 → 순수 판정을 `market/BarCompleteness` 로 새로 둔다.
- **공휴일 캘린더 없음**(#876). 가드는 "세션이 끝났는가"만 보고 "세션이 있었는가"는 보지
  않는다 — 휴장일엔 캔들 자체가 오지 않으므로 문제되지 않는다.

## 5. 아키텍처 개요

```
TossCandle ──▶ DailyCollector.toDaily(symbol, c)
                 │  d = tradeDate(c)                  (ET/KST 날짜)
                 │  market = BarCompleteness.marketOf(symbol)
                 │
                 ├─ BarCompleteness.sessionClosed(market, d, now) == false
                 │     └─▶ return null   ───▶ upsert 안 함 (AC1)
                 │
                 └─ true ─▶ new DailyOhlcv(...) ──▶ dailyMapper.upsert
```

**I/O ↔ 순수 경계**: `BarCompleteness` 는 시계도 DB도 모른다 — `now` 를 인자로 받는 순수
함수다(테스트에서 시각을 고정한다). `toDaily` 만 `LocalDateTime.now()` 를 읽는다.

## 6. 데이터 모델

스키마 변경 **없음**. 저장 여부만 달라진다.

| 입력 | 타입 | 검증 |
|---|---|---|
| `market` | String | `KR`/`US`. 그 외는 US 규칙 적용(보수적 — 더 늦게 인정) |
| `tradeDate` | LocalDate | null → `false`(저장 안 함) |
| `kstNow` | LocalDateTime | null → `false` |

## 7. 함수 명세

| 함수 | 책임(1줄) | 시그니처 | 입력 | 출력 | 에러/실패 | 복잡? |
|------|-----------|----------|------|------|-----------|-------|
| `BarCompleteness.sessionClosed` | 그 거래일 세션이 끝났는지 | `static boolean sessionClosed(String market, LocalDate tradeDate, LocalDateTime kstNow)` | §6 | boolean | null 입력 → false | **복잡** → `fn-sessionClosed.md` |
| `BarCompleteness.marketOf` | 종목코드 → 시장 | `static String marketOf(String symbol)` | 종목코드 | `KR`/`US` | null → `US` | 단순 |
| `DailyCollector.toDaily` | 캔들 → 일봉 행(가드 추가) | 기존 시그니처 유지 | | `DailyOhlcv` 또는 null | 미완성 → null | 단순(위임) |

## 8. 흐름 / 알고리즘

```
sessionClosed(market, d, now):
  if (d == null || now == null)        → false
  if (US)  return now >= d.plusDays(1).atTime(07:00)
  else     return now >= d.atTime(15:30)
```

- KR: 15:40 정기 수집은 15:30 임계를 통과한다(여유 10분) → AC2.
- US: ET 날짜 D 바는 KST D+1 07:00 이후에만 저장된다. 즉 US 장중(22:30~05:00 KST)에는
  최신 완성 바가 D-1 이다 — **KR 이 장중에 전일 바를 쓰는 것과 같은 구조**가 된다.

## 9. 엣지케이스 & 에러 처리

| 상황 | 처리 |
|---|---|
| 과거 바(백필) | `now` 가 훨씬 뒤이므로 항상 통과 → AC3 |
| 거래일 파싱 실패 | `toDaily` 가 이미 null 반환(기존 동작) → AC5 |
| 시장 판정 불가(이상한 심볼) | US 규칙(더 늦게 인정) — 보수적 |
| 장중 `backfillSymbol` 호출 | 그날 부분 바를 저장하지 않는다(개선) |
| 주말 수집 | 캔들이 안 오므로 무관. 와도 임계 통과 여부로만 판정 |
| 서머타임 전환일 | D+1 07:00 은 05:00·06:00 두 경우를 모두 덮는다 |
| 기존에 저장된 미완성 바 | 다음 수집 때 완성값으로 덮어써진다(현재도 그렇게 동작). 별도 정정 불필요 |

## 10. 테스트 계획

### `BarCompletenessTest` (순수)

| 케이스 | 매핑 |
|---|---|
| KR 15:29 는 미완성, 15:30·15:40 은 완성 | AC1·AC2 |
| KR 전날 바는 완성 | AC3 |
| US D 바는 D+1 06:59 에 미완성, 07:00 에 완성 | AC1 |
| US 장중(D 23:00 KST)에 D 바는 미완성 | AC1 |
| US D-1 바는 D 07:00 이후 완성 | AC2 |
| 거래일/현재시각 null → false | AC5 |
| 6자리 숫자 → KR, 그 외 → US | — |

### `DailyCollectorTest`

| 케이스 | 매핑 |
|---|---|
| 미완성 US 캔들 → `toDaily` 가 null | AC1 |
| 완성 US 캔들 → 행 생성 | AC2 |
| 완성 KR 캔들 → 행 생성(회귀) | AC2 |

### 배포 후 검증 (AC4)

가드 적용 후 다음 수집 사이클 뒤 US 최신 바 거래량/20일평균 중위를 재측정한다.
**0.3 이상**이면 통과. (현재 0.1252)

## 11. 배포 / 롤백

- 코드 가드이므로 롤백은 git revert.
- 즉시 효과는 "US 최신 바를 더 이상 덧쓰지 않음"이다. 이미 들어있는 10-08 미완성 바는
  다음 정기 수집(15:40)에 완성값으로 덮어써진다 — 그 시점부터 정상화된다.
