# 함수 설계서: `BarCompleteness.sessionClosed` (#885)

> **부모 설계서**: ./README.md · **상태**: Draft
> **작성**: [AI] Architect · **구현**: `market/BarCompleteness.java:sessionClosed`
> **테스트**: `market/BarCompletenessTest.java`

## 1. 시그니처

```java
public static boolean sessionClosed(String market, LocalDate tradeDate, LocalDateTime kstNow)
```

## 2. 책임 (단일 책임, 1줄)

주어진 거래일의 **정규장이 이미 끝났는지** 판정한다 — 즉 그 날짜의 일봉을 완성본으로 믿어도 되는지.

## 3. 입력

| 파라미터 | 타입 | 제약/검증 | 설명 |
|----------|------|-----------|------|
| `market` | String | `KR`/`US`. 그 외·null 은 **US 규칙** 적용 | 캔들이 속한 시장 |
| `tradeDate` | LocalDate | null → `false` | 캔들의 거래일. US 는 **ET 날짜** |
| `kstNow` | LocalDateTime | null → `false` | 현재 시각(KST). 호출부가 주입 |

## 4. 출력

- **반환**: `true` = 세션 종료(저장 가능) / `false` = 미완성(저장 금지).
- **부수효과**: 없음 — **순수 함수**. 시계를 읽지 않는다(테스트에서 시각 고정 가능).

## 5. 동작 / 알고리즘

```
if (tradeDate == null || kstNow == null) return false;

if (US)  return !kstNow.isBefore(tradeDate.plusDays(1).atTime(07:00));
else     return !kstNow.isBefore(tradeDate.atTime(15:30));
```

### 임계값 근거

| 시장 | 정규장 마감 | 임계(KST) | 왜 |
|---|---|---|---|
| KR | 15:30 KST | 거래일 **15:30** | 정기 수집이 15:40 이라 여유 10분. 현재 KR 바는 실측상 정확하므로 회귀를 내지 않는 값이어야 한다 |
| US | 16:00 ET | **거래일+1일 07:00** | 16:00 ET 의 KST 환산은 서머타임에 따라 05:00(여름)~06:00(겨울)로 변한다. 07:00 은 두 경우를 모두 덮는다 |

**왜 서머타임을 정확히 계산하지 않는가**: 오차의 방향이 비대칭이다. 임계를 너무 이르게 잡으면
**미완성 바를 완성으로 받아들여** 잘못된 매수 판정을 낳는다(지금의 버그 그 자체). 너무 늦게
잡으면 완성 바 저장이 최대 2시간 늦어질 뿐이고, US 장중(22:30~05:00 KST)에는 어차피 전일
완성 바로 판정하는 것이 맞다 — KR 이 장중에 전일 바를 쓰는 것과 같은 구조다.
그래서 **늦게 인정하는 쪽**으로만 오차를 낸다.

**왜 `MarketHours` 를 재사용하지 않는가**: `MarketHours` 는 `autotrade` 패키지에 있고
US 를 22:30~05:00 KST 고정으로 단순화해 둔다. `market` 패키지가 `autotrade` 를 의존하면
레이어 방향이 뒤집히고, 그 단순화(05:00 고정)는 여기서 쓰면 안 되는 값이다.

## 6. 에러 & 실패 모드

| 조건 | 처리 | 반환 |
|------|------|------|
| `tradeDate == null` | 저장 금지 | `false` |
| `kstNow == null` | 저장 금지 | `false` |
| `market` 이 null·미지 문자열 | US 규칙(더 보수적) | 계산값 |

**fail-closed**: 판정할 수 없으면 저장하지 않는다. 데이터가 하루 늦게 들어오는 비용과
잘못된 바로 실거래 판정을 하는 비용이 비대칭이기 때문이다.

## 7. 엣지케이스

- **과거 거래일** — `kstNow` 가 임계보다 훨씬 뒤이므로 항상 `true`. 백필 영향 없음.
- **미래 거래일**(시계 오차·API 이상) — `false`. 저장되지 않는다.
- **주말/휴장일** — 휴장일엔 캔들이 오지 않는다. 와도 임계 비교로만 판정하며, 이 함수는
  "세션이 있었는가"를 알지 못한다(공휴일 캘린더 없음 — #876).
- **정확히 임계 시각** — `!isBefore` 이므로 **포함**(15:30:00 은 완성).
- **US 거래일이 ET 금요일** — D+1 = 토요일 07:00 KST. 그 시점이면 장은 이미 끝났다(금 16:00
  ET = 토 05:00/06:00 KST). 올바르다.

## 8. 복잡도 / 성능

- O(1), 할당 없음(`LocalDate.atTime` 1회).
- 호출 빈도: 캔들 1건당 1회. 정기 수집은 3,745종목 × 2캔들 ≈ 7,490회/일 — 무시 가능.
