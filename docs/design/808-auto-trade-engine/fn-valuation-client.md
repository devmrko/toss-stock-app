# 함수 설계서: `ValuationClient.{getValuation,getAnnualFinancials}` / `ValuationChecker.isUndervalued` (#808 후속)

> **부모 설계서**: ./README.md · **상태**: Approved
> **작성**: [AI] Architect · **구현**: `com.cloudhandson.tossstock.autotrade.{ValuationClient,ValuationChecker}` · **테스트**: `ValuationCheckerTest`, `EarningsQualityCheckerTest`
> **배경(2026-09-25)**: 원래 설계서(§2 제외 항목)에서 "저평가 자동판정은 데이터 소스 없어 수동 큐레이션"으로 뒀으나,
> 사용자 지적으로 실측 확인 — 네이버 모바일 API(KR)·야후 파이낸스 API(US) 둘 다 비공식이지만 실제 PER/PBR 제공 확인됨.
> **갱신(2026-09-30)**: `getAnnualFinancials`가 KR(네이버)만 지원해 US 종목은 항상 `null` → 실적/재무/자본배분 3개 체크가
> 구조적으로 전부 false 처리되어 미국 종목은 펀더멘털 점수가 2/5(=min-fundamental-pass 3 미달)에 고정, **어떤 미국
> 종목도 매수될 수 없던 버그**를 사용자가 "다른건 없었어? 살만한거" 질의로 LOCO를 조사하다 발견. 야후 실측 확인 후
> US 경로 추가, `EarningsQualityChecker`에 영업이익 데이터 없을 때의 대체 판정 추가(§5, §7).

## 1. 시그니처
```java
Valuation getValuation(String symbol, String market)                    // I/O, market: "KR"|"US"
boolean isUndervalued(Valuation v, double maxPer, double maxPbr)        // 순수 함수
AnnualFinancials getAnnualFinancials(String symbol, String market)      // I/O, market: "KR"|"US"
```

## 2. 책임 (단일 책임, 1줄)
- `getValuation`: 종목의 현재 PER/PBR을 외부 소스(KR=네이버, US=야후)에서 조회.
- `isUndervalued`: 조회된 PER/PBR이 둘 다 임계값 이하인지 순수 판정.
- `getAnnualFinancials`: 최근 실결산 연도별 매출/영업이익/순이익/부채비율/배당을 외부 소스(KR=네이버, US=야후)에서 조회 — `EarningsQualityChecker`/`BalanceSheetChecker`/`CapitalReturnChecker`(펀더멘털 체크리스트 5항목 중 3항목)의 유일한 데이터 공급원.

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
- `getAnnualFinancials` 반환: `AnnualFinancials(List<Year>)` — 과거→최근 순, 각 `Year(label, revenue, operatingProfit, netIncome, debtRatio, dividendPerShare)`. 조회 실패 시 전체 `null`. 개별 필드는 소스가 못 주면 `null`(특히 US/야후는 `operatingProfit`·`debtRatio`가 거의 항상 `null` — §7).

## 5. 동작 / 알고리즘
**네이버(KR)**: `GET https://m.stock.naver.com/api/stock/{symbol}/integration` → `totalInfos` 배열에서 `code="per"`/`code="pbr"` 항목의 `value`("12.85배") 파싱(배 제거 후 숫자 변환).

**야후(US)**: 크럼(crumb) 필요 —
1. `GET https://fc.yahoo.com`(쿠키 획득, `CookieManager`가 자동 보관)
2. `GET https://query1.finance.yahoo.com/v1/test/getcrumb`(응답 본문 = 크럼 문자열)
3. `GET .../v10/finance/quoteSummary/{symbol}?modules=defaultKeyStatistics,summaryDetail&crumb={crumb}` → `defaultKeyStatistics.priceToBook.raw`, `summaryDetail.trailingPE.raw`
4. 크럼은 인스턴스에 캐시(`volatile` 필드), 401/빈 결과 시 1회 재발급 후 재시도.

**판정**: PER≤maxPer **AND** PBR≤maxPbr **AND** 둘 다 양수(적자 등 음수 PER 제외) → true.

**연간 실적(`getAnnualFinancials`, 2026-09-30 추가)**:
- **KR(네이버)**: `GET .../finance/annual` → `trTitleList`에서 `isConsensus="N"`(실결산, 추정 제외)인 연도 키만 추출, `rowList`에서 매출액/영업이익/당기순이익/부채비율/주당배당금 셀 값 파싱. 최대 3개년.
- **US(야후)**: 크럼은 `getValuation`과 공유(동일 인스턴스 캐시). `GET .../quoteSummary/{symbol}?modules=incomeStatementHistory,balanceSheetHistory,summaryDetail&crumb={crumb}` 1회 호출로 매출/영업이익/순이익(`incomeStatementHistory`)·배당(`summaryDetail.dividendRate`)·부채비율 산정용 원본(`balanceSheetHistory`) 동시 확보. 야후는 **최신→과거 순**으로 주므로 뒤집어서 과거→최근으로 정렬. 부채비율 = `totalLiab/totalStockholderEquity*100`(`endDate` 기준으로 손익표 연도와 매칭), 둘 중 하나라도 없으면 `null`.

## 6. 에러 & 실패 모드
| 조건 | 처리 | 반환/예외 |
|------|------|-----------|
| 외부 API 타임아웃/5xx/파싱실패 | 예외를 잡아서 로그(WARN)만 남김 | `getValuation`/`getAnnualFinancials` → `null` |
| 야후 크럼 만료(401 유사) | 1회 재발급 후 재시도 | 재시도도 실패하면 `null` |
| 한국 종목인데 야후 데이터 없음(반대로 미국 종목에 네이버 호출 등 시장 구분 오류) | market 파라미터로 애초에 분기 | 호출측이 `AutoTradeCandidate.market` 사용 |
| PER/PBR 조회 자체는 됐는데 값이 이상(음수, 0) | `isUndervalued`에서 안전 쪽(false) | 매수 안 함 |
| 조회 결과 `null`(완전 실패) | `isUndervalued(null, ...)` → false | 매수 안 함(**fail-closed** — 데이터 없으면 사지 않는다, 이게 중요) |
| US 종목의 영업이익/부채비율이 야후 무료 API 한계로 항상 `null` | `EarningsQualityChecker`가 3년치 `operatingProfit`이 전부 `null`이면 매출+순이익만으로 대체 판정(fn 없음, 로직은 `EarningsQualityChecker.hasThreeYearUptrend` 참고). `BalanceSheetChecker`는 부채비율 `null`이면 그대로 fail-closed(false) — 완화 없음 | 실적 체크는 대체 판정, 재무건전성 체크는 자동 실패(보수적) |

## 7. 엣지케이스
- 네이버가 PER/PBR을 "-"로 주는 경우(적자 기업 등): 숫자 파싱 실패 → 해당 필드 null → `isUndervalued` false.
- 야후가 한국 종목에 대해 데이터 자체를 안 주는 경우(2026-09-25 실측: 005930.KS, 000660.KS 둘 다 빈 값) — 애초에 KR은 야후를 안 쓰고 네이버로 분기하므로 해당 없음.
- 비공식 API가 응답 스키마를 바꾸는 경우: 파싱 실패 → null → fail-closed(매수 안 함, 크래시 안 함).
- **야후 무료 API의 `operatingIncome`/`balanceSheetHistory` 필드가 실측상(2026-09-30, LOCO 4개년 전부) 항상 `null`** — 유료/공식 API에서만 제공되는 것으로 추정. `totalRevenue`/`netIncome`은 신뢰 가능, `dividendRate`는 실제 배당이 있을 때만 채워짐(정상). 이 데이터 한계 자체가 버그가 아니라 **소스의 근본적 제약**이므로, 향후 야후 응답 스키마가 바뀌어 영업이익을 주기 시작해도 코드는 그대로 정상 동작(단순히 대체 판정 분기를 안 타게 될 뿐).

## 8. 복잡도 / 성능
- 네이버(PER/PBR): HTTP 1회. 네이버(연간실적): HTTP 1회(별도 엔드포인트). 야후(PER/PBR·연간실적 모두): 크럼 캐시 히트 시 종목당 HTTP 1회씩, 미스 시 재발급 포함. 후보 스캔 시(장중 5분 틱, 활성 후보 수만큼) 호출 — 후보가 많지 않은 v1 규모(5종목 이하)에선 부담 적음.

## 9. 의존성
- `application.yml`: `auto-trade.max-per`(20.0), `auto-trade.max-pbr`(2.0), `auto-trade.max-debt-ratio`(200.0), `auto-trade.min-fundamental-pass`(3) — "운영하며 조정" 대상, 확정값 아님.
- 외부: `m.stock.naver.com`(비공식), `query1.finance.yahoo.com`(비공식) — **둘 다 공식 API 아님, 언제든 깨질 수 있음**. 실패해도 앱 전체엔 영향 없음(fail-closed).

## 10. 테스트 케이스
- [ ] `isUndervalued`: PER/PBR 둘 다 임계값 이하 → true
- [ ] `isUndervalued`: PER만 초과 → false
- [ ] `isUndervalued`: PBR만 초과 → false
- [ ] `isUndervalued`: 둘 다 경계값과 정확히 일치 → true
- [ ] `isUndervalued`: v가 null → false
- [ ] `isUndervalued`: PER 또는 PBR이 null → false
- [ ] `isUndervalued`: PER 음수(적자) → false
- [x] `EarningsQualityChecker.hasThreeYearUptrend`: 영업이익 3년 전부 null(US 야후 케이스) + 매출·순이익 우상향 + 최근 순이익 흑자 → true
- [x] 위 케이스에서 순이익 정체/감소 또는 적자 → false
- [x] 영업이익이 일부 연도만 null(일부라도 데이터 있음) → 대체 판정으로 안 빠지고 원래 엄격 로직 그대로 적용
- [ ] `ValuationClient`는 실제 외부 API 의존이라 단위테스트 대상 아님(수동 검증: 2026-09-25 삼성전자/해성디에스/AAPL, 2026-09-30 LOCO `incomeStatementHistory`/`balanceSheetHistory`/`summaryDetail` 실제 호출 확인 완료).

## 11. 추적성
- 인수조건: #808 원 설계서 §2 제외 항목("저평가 자동판정 — 후속 이슈")을 이번에 조기 해소.
- 2026-09-30: `getAnnualFinancials`의 KR 전용 제약을 제거해 §2 제외 항목이 아니었던 **숨은 버그**(US 펀더멘털 점수 영구 2/5 고정)를 해소. 함께 발견된 `LiquidityChecker` 통화 불일치(USD 거래대금을 KRW 임계값과 비교)는 `auto-trade.min-avg-trading-value-usd` 추가로 해소 — README.md §11/§9 참고.
- 관련: `docs/journal/auto-trade-screening.md`(실측 근거 기록).
