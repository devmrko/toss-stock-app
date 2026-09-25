# 함수 설계서: `ValuationClient.getValuation` / `ValuationChecker.isUndervalued` (#808 후속)

> **부모 설계서**: ./README.md · **상태**: Approved
> **작성**: [AI] Architect · **구현**: `com.cloudhandson.tossstock.autotrade.{ValuationClient,ValuationChecker}` · **테스트**: `ValuationCheckerTest`
> **배경(2026-09-25)**: 원래 설계서(§2 제외 항목)에서 "저평가 자동판정은 데이터 소스 없어 수동 큐레이션"으로 뒀으나,
> 사용자 지적으로 실측 확인 — 네이버 모바일 API(KR)·야후 파이낸스 API(US) 둘 다 비공식이지만 실제 PER/PBR 제공 확인됨.

## 1. 시그니처
```java
Valuation getValuation(String symbol, String market)   // I/O, market: "KR"|"US"
boolean isUndervalued(Valuation v, double maxPer, double maxPbr)   // 순수 함수
```

## 2. 책임 (단일 책임, 1줄)
- `getValuation`: 종목의 현재 PER/PBR을 외부 소스(KR=네이버, US=야후)에서 조회.
- `isUndervalued`: 조회된 PER/PBR이 둘 다 임계값 이하인지 순수 판정.

## 3. 입력
| 파라미터 | 타입 | 제약/검증 | 설명 |
|----------|------|-----------|------|
| `symbol` | String | KR 6자리 또는 US 티커 | |
| `market` | String | "KR" 또는 "US"(대소문자 무관) | |
| `v` | Valuation | null 허용(조회 실패 시) | |
| `maxPer`, `maxPbr` | double | > 0 | `application.yml`의 `auto-trade.max-per`/`max-pbr` |

## 4. 출력
- `getValuation` 반환: `Valuation(per, pbr)` — 둘 다 BigDecimal, 조회/파싱 실패 시 해당 필드 또는 전체가 `null`.
- `isUndervalued` 반환: `boolean`. **부수효과 없음(순수)**.
- `getValuation`의 부수효과: 외부 HTTP 호출 2~3건(야후는 크럼 발급 포함), 예외를 던지지 않고 실패 시 `null` 반환(호출측 스케줄러가 죽지 않도록).

## 5. 동작 / 알고리즘
**네이버(KR)**: `GET https://m.stock.naver.com/api/stock/{symbol}/integration` → `totalInfos` 배열에서 `code="per"`/`code="pbr"` 항목의 `value`("12.85배") 파싱(배 제거 후 숫자 변환).

**야후(US)**: 크럼(crumb) 필요 —
1. `GET https://fc.yahoo.com`(쿠키 획득, `CookieManager`가 자동 보관)
2. `GET https://query1.finance.yahoo.com/v1/test/getcrumb`(응답 본문 = 크럼 문자열)
3. `GET .../v10/finance/quoteSummary/{symbol}?modules=defaultKeyStatistics,summaryDetail&crumb={crumb}` → `defaultKeyStatistics.priceToBook.raw`, `summaryDetail.trailingPE.raw`
4. 크럼은 인스턴스에 캐시(`volatile` 필드), 401/빈 결과 시 1회 재발급 후 재시도.

**판정**: PER≤maxPer **AND** PBR≤maxPbr **AND** 둘 다 양수(적자 등 음수 PER 제외) → true.

## 6. 에러 & 실패 모드
| 조건 | 처리 | 반환/예외 |
|------|------|-----------|
| 외부 API 타임아웃/5xx/파싱실패 | 예외를 잡아서 로그(WARN)만 남김 | `getValuation` → `null` |
| 야후 크럼 만료(401 유사) | 1회 재발급 후 재시도 | 재시도도 실패하면 `null` |
| 한국 종목인데 야후 데이터 없음(반대로 미국 종목에 네이버 호출 등 시장 구분 오류) | market 파라미터로 애초에 분기 | 호출측이 `AutoTradeCandidate.market` 사용 |
| PER/PBR 조회 자체는 됐는데 값이 이상(음수, 0) | `isUndervalued`에서 안전 쪽(false) | 매수 안 함 |
| 조회 결과 `null`(완전 실패) | `isUndervalued(null, ...)` → false | 매수 안 함(**fail-closed** — 데이터 없으면 사지 않는다, 이게 중요) |

## 7. 엣지케이스
- 네이버가 PER/PBR을 "-"로 주는 경우(적자 기업 등): 숫자 파싱 실패 → 해당 필드 null → `isUndervalued` false.
- 야후가 한국 종목에 대해 데이터 자체를 안 주는 경우(2026-09-25 실측: 005930.KS, 000660.KS 둘 다 빈 값) — 애초에 KR은 야후를 안 쓰고 네이버로 분기하므로 해당 없음.
- 비공식 API가 응답 스키마를 바꾸는 경우: 파싱 실패 → null → fail-closed(매수 안 함, 크래시 안 함).

## 8. 복잡도 / 성능
- 네이버: HTTP 1회. 야후: 크럼 캐시 히트 시 HTTP 1회, 미스 시 3회(쿠키+크럼+본조회). 후보 스캔 시(장중 5분 틱, 활성 후보 수만큼) 호출 — 후보가 많지 않은 v1 규모(5종목 이하)에선 부담 적음.

## 9. 의존성
- `application.yml`: `auto-trade.max-per`(20.0), `auto-trade.max-pbr`(2.0) — "운영하며 조정" 대상, 확정값 아님.
- 외부: `m.stock.naver.com`(비공식), `query1.finance.yahoo.com`(비공식) — **둘 다 공식 API 아님, 언제든 깨질 수 있음**. 실패해도 앱 전체엔 영향 없음(fail-closed).

## 10. 테스트 케이스
- [ ] `isUndervalued`: PER/PBR 둘 다 임계값 이하 → true
- [ ] `isUndervalued`: PER만 초과 → false
- [ ] `isUndervalued`: PBR만 초과 → false
- [ ] `isUndervalued`: 둘 다 경계값과 정확히 일치 → true
- [ ] `isUndervalued`: v가 null → false
- [ ] `isUndervalued`: PER 또는 PBR이 null → false
- [ ] `isUndervalued`: PER 음수(적자) → false
- [ ] `ValuationClient`는 실제 외부 API 의존이라 단위테스트 대상 아님(수동 검증: 2026-09-25 삼성전자/해성디에스/AAPL로 실제 호출 확인 완료).

## 11. 추적성
- 인수조건: #808 원 설계서 §2 제외 항목("저평가 자동판정 — 후속 이슈")을 이번에 조기 해소.
- 관련: `docs/journal/auto-trade-screening.md`(실측 근거 기록).
