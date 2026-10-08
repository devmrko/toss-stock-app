# 설계서: 수수료 요율 동적 조회 (#863)

> **상태**: Approved · **작성**: [AI] Architect · 2026-10-08
> **추적성** — Redmine: #863 · 구현: `TossCommission`(신규), `TossApiClient`,
>   `CommissionRateCache`(신규), `TradingFeeCalculator`, `OrderExecutor`,
>   `RangeOrderExecutor`, `MarketController` ·
>   테스트: `CommissionRateCacheTest`(신규), `TradingFeeCalculatorTest`

## 1. 목적 (Why)

`TradingFeeCalculator` 가 수수료율을 **상수로 들고 있다**.

```java
private static final BigDecimal KR_COMMISSION_RATE = new BigDecimal("0.00015");
private static final BigDecimal US_COMMISSION_RATE = new BigDecimal("0.001");
```

`GET /api/v1/commissions` 실측(2026-10-08, 앱 경로):

| marketCountry | commissionRate | startDate | endDate |
|---------------|----------------|-----------|---------|
| KR | `0.00015` | 2021-01-01 | `9999-12-31` |
| US | `0.001` | null | **`2026-10-09`** |

**현재 요율은 상수와 일치한다 — 지금 계산은 틀리지 않았다.** 문제는 US 의 `endDate` 다.
#863 등록 시점에는 `2026-10-08` 이었는데 지금 `2026-10-09` 로 **하루 밀렸다**. 요율 변경
공지가 아니라 **"현재 유효"를 표현하는 롤링 값**으로 보인다.

어느 쪽이든 결론은 같다 — **요율을 코드에 박아두면 바뀌는 순간 조용히 틀려진다.**
그 틀림은 즉시 드러나지 않고 실현손익 집계에서 뒤늦게 발견된다. 2026-10-08 에
누적 실현손익을 토스 숫자(−214,489원)와 맞추느라 수수료 귀속 버그를 다섯 번에 걸쳐
잡았다 — 같은 종류의 비용을 또 치르지 않기 위해 **읽어서 쓴다**.

## 2. 설계 (What)

### 2.1 1일 캐시 조회

#847 에서 "매 주문마다 조회"는 레이트리밋 때문에 기각했다. 요율은 연 단위로도 거의
바뀌지 않으므로 **1일 캐시**면 충분하고 호출 부담이 없다.

```
OrderExecutor → CommissionRateCache.rateFor(market) → (캐시 만료 시) TossApiClient.getCommissions()
                                                     → 실패/부재 시 하드코딩 기본값
```

### 2.2 실패 시 거동 — 기존 동작으로 폴백 (fail-safe)

| 상황 | 거동 |
|------|------|
| 조회 성공, 해당 market 있음 | **조회값 사용** |
| 조회 성공, `endDate` 가 과거 | **조회값 사용 + 경고 로그** (아래) |
| 조회 실패(네트워크·인증·HTTP) | **하드코딩 기본값** 사용 |
| 조회 성공, 해당 market 없음 | **하드코딩 기본값** 사용 |

`endDate` 가 지났어도 **조회값을 쓴다** — 롤링 값으로 보이므로(§1) 만료 자체가
"이 값이 틀렸다"는 뜻이 아니고, 어차피 하드코딩 상수보다 조회값이 최신이다.
다만 요율 체계가 실제로 바뀐 신호일 수도 있으니 **경고 로그를 남겨 눈에 띄게** 한다.

조회 실패 시 기본값으로 떨어지는 것이 중요하다 — **수수료를 계산하지 못해 주문을
막으면 안 된다**(손절이 멈춘다). 수수료는 기록·집계용이고 주문 자체의 전제가 아니다.

### 2.3 `TradingFeeCalculator` 는 순수 함수를 유지한다

요율을 받는 **오버로드를 추가**하고, 기존 2-인자 버전은 하드코딩 기본값으로 위임한다.
계산기가 DB·API 를 모르게 유지해야 테스트가 지금처럼 단순하게 남는다.

```java
public static BigDecimal commission(BigDecimal filledAmount, String market)                      // 기존
public static BigDecimal commission(BigDecimal filledAmount, String market, BigDecimal rate)      // 신규
public static BigDecimal defaultCommissionRate(String market)                                     // 폴백 노출
```

세금(`tax`)은 손대지 않는다 — KR 증권거래세 0.20% 와 US SEC fee 는 `/commissions` 가
주는 값이 아니고 규제기관 고시다(#847/#861).

### 2.4 반올림은 그대로

통화별 반올림(KR 정수, US 소수 2자리)은 #861 에서 정한 그대로다. 요율만 주입된다.

## 3. 함수 등재 (Function Registry)

| 함수 | 파일 | 책임 | I/O | 비고 |
|------|------|------|-----|------|
| `record TossCommission(String marketCountry, String commissionRate, String startDate, String endDate)` | `toss/dto/TossCommission.java` (신규) | 요율표 1행 | 순수 | 응답 그대로(문자열) |
| `TossApiClient.getCommissions()` | 기존 수정 | 요율표 조회 | I/O | `getCommissionsRaw` 를 타입화로 교체 |
| `CommissionRateCache.rateFor(String market)` | `autotrade/CommissionRateCache.java` (신규) | 1일 캐시 + 폴백 | I/O | §2.2 |
| `CommissionRateCache.pick(List<TossCommission>, String market)` | 동일 | 목록에서 market 행 선택·파싱 | **순수** | 테스트 본체 |
| `TradingFeeCalculator.commission(amount, market, rate)` | 기존 수정 | 요율 주입 오버로드 | 순수 | |
| `TradingFeeCalculator.defaultCommissionRate(market)` | 기존 수정 | 폴백 요율 노출 | 순수 | |
| `OrderExecutor` / `RangeOrderExecutor` | 기존 수정 | 캐시에서 요율 받아 전달 | I/O | |
| `MarketController.commissions()` | 기존 수정 | 요율표 원문 조회(운영 확인용) | I/O | 타입화 반영 |

## 4. 인수조건 (Acceptance)

1. 요율 조회 성공 시 **조회값**으로 수수료가 계산된다.
2. 조회 실패 시 **하드코딩 기본값**으로 계산되고 주문은 정상 진행된다.
3. 조회 결과에 해당 market 이 없으면 기본값을 쓴다.
4. `endDate` 가 과거면 조회값을 쓰되 **경고 로그**를 남긴다.
5. 같은 날 반복 호출 시 API 를 한 번만 부른다(1일 캐시).
6. KR 수수료 계산 결과가 변경 전과 **동일**하다(조회값 0.00015 = 상수).
7. 세금 계산은 변경되지 않는다.

## 5. 테스트 계획

`CommissionRateCacheTest`:
- `pick`: KR 행이 있으면 `0.00015` 반환
- `pick`: 목록에 해당 market 없음 → null (호출부가 기본값으로 폴백)
- `pick`: `commissionRate` 가 null·빈값·비(非)숫자 → null
- `pick`: 대소문자 무관(`us` / `US`)
- `rateFor`: 조회 성공 → 조회값
- `rateFor`: API 예외 → 기본값, 예외 전파 없음 (인수조건 2)
- `rateFor`: 두 번 호출 시 API 1회만 (인수조건 5)
- `rateFor`: `endDate` 과거여도 조회값 사용 (인수조건 4)

`TradingFeeCalculatorTest`:
- `commission(1,000,000, "KR", 0.0002)` → 200 (주입 요율 적용)
- 기존 2-인자 테스트는 그대로 통과 (인수조건 6)
- `defaultCommissionRate("KR"/"US"/null)` 값 고정

## 6. 리스크 / 되돌리기

| 리스크 | 완화 |
|--------|------|
| 조회값이 비정상(0, 음수, 1 초과)이면 수수료가 망가짐 | `pick` 에서 `0 < rate < 0.01`(1%) 범위 밖은 null 처리 → 기본값 폴백 |
| 조회 실패가 주문을 막음 | §2.2 — 예외를 삼키고 기본값. 수수료는 기록용이며 주문의 전제가 아니다 |
| 캐시가 하루 묶여 당일 변경을 놓침 | 요율 변경은 사전 공지되는 사안이고 1일 지연은 수용 가능. 즉시 반영이 필요하면 앱 재기동 |
| 과거 주문로그의 수수료가 재계산되지 않음 | 의도된 것 — 체결 시점 요율로 기록돼야 한다 |

되돌리기: `OrderExecutor` 가 3-인자 대신 2-인자 `commission` 을 호출하도록 1줄 환원.

## 7. 범위 밖

- 세금·제비용(KR 증권거래세, US SEC fee/FINRA TAF) 동적화 — `/commissions` 가 주지
  않는다. FINRA TAF 2027-01-01 재개는 #862.
- 과거 주문로그 수수료 소급 재계산.
