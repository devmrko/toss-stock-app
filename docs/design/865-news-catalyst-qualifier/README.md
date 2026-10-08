# 설계서: 뉴스 촉매 자격 판정 — 2단 깔때기 (#865)

> **상태**: Approved · **작성**: [AI] Architect · 2026-10-08
> **추적성** — Redmine: #865 · 근거: `docs/reference/investment-principles.md` §3, §9 ·
>   구현: `NewsFacts`(신규), `CatalystQualifier`(신규), `NewsClassifier`,
>   `StockNewsMapper`(+XML), `SchemaInitializer`, `CandidateDiscoveryService` ·
>   테스트: `CatalystQualifierTest`(신규), `NewsClassifierTest`, `CandidateDiscoveryServiceTest`

## 1. 목적 (Why)

현재 뉴스 게이트는 `CandidateDiscoveryService.java:84` 의 **단 한 줄**이다.

```java
String level = NewsSignals.levelOf(n.getSentiment(), symbol);
if (level == null || Integer.parseInt(level.substring(1)) < 4) continue;
```

즉 **기사 1건의 S4 등급 = 매수 후보 등록**이다. 실측(KR, 최근 1일):

| 단계 | 건수 | 비율 |
|------|------|------|
| 수집 기사 | 870 | 100% |
| `kind=EVENT` | 654 | 75% |
| **EVENT + S4↑ (= 현재 통과)** | **324** | **37%** |
| 후보 등록 | 1,775 | — |
| 실매수 | 49 | — |

37%는 "호재 판별"이 아니라 사실상 무필터다. 실제로 통과한 기사에는
`"목표가↑"-iM`(애널리스트 의견), `키움증권 연 5.8% 발행어음 특판`(상품 홍보),
`[특징주] 화장품주 강세`(사후 보도)가 섞여 있다.

### 1.1 원칙 문서와의 구조적 괴리 (핵심)

`investment-principles.md` §3 체크리스트는 7항목이고, **뉴스가 기여하는 것은 1번의
'구체 촉매' 하나뿐**이다.

| § | 항목 | 성질 |
|---|------|------|
| 3-1 | 이야기(불변 테마 + 수출 + **구체 촉매**) | 일부 뉴스 |
| 3-2 | 3년 실적 우상향·핵심사업 흑자 | 회사 |
| 3-3 | 재무건전성·**유증/회사채 남발 없음** | 회사 |
| 3-4 | 주주환원 계획 | 회사 |
| 3-5 | 5년 분쟁·규제·소송 Low | 회사 |
| 3-6 | 시총·거래대금 충분 | 회사 |
| 3-7 | 상대강세·재평가 여지 | 가격 |

그런데 봇은 **뉴스 1건으로 매수를 결정**한다. 등급 프롬프트를 아무리 손봐도 이 구조는
고쳐지지 않는다 — 그래서 **게이트를 2단으로 분리**한다.

## 2. 설계 (What)

```
기사 → [NewsClassifier] level + facts 추출·저장
                              ↓
        1단: [CatalystQualifier] 촉매 자격 판정 (원칙 §3 규칙)  ← 본 이슈
                              ↓ 통과분만
        2단: [FundamentalScore·ValuationChecker·PriceExtension] 종목 판정 (기존)
                              ↓
                           매수
```

**2단은 1단과 독립**이다 — 좋은 뉴스가 나쁜 회사를 통과시키지 못한다(§3-2~3-7).
본 이슈는 1단만 신설하고 2단은 기존 구현을 그대로 둔다.

### 2.1 추출 사실 (`NewsFacts`)

`level`(S1~S5) 단독은 신뢰할 수 없음이 확인됐다(정의문을 빼면 등급이 붕괴). 그래서
**해석이 아니라 사실**을 뽑고, 판정은 코드의 규칙으로 한다(설명 가능성 확보).

| 필드 | 뜻 | 원칙 근거 |
|------|-----|-----------|
| `confirmed` | 이미 확정된 사실(계약체결·실적발표·승인). 전망·목표가·추진·검토는 false | §3-1 |
| `isTransaction` | 거래 기사(공급·수주·납품·인수·매각)인가 | — (교정 A) |
| `materialAmount` | **매출·이익에 직결되는** 금액·수량이 숫자로 제시됨 | §3-1 (교정 B) |
| `recurring` | 반복·지속 매출(장기공급계약, 구조적 수요) | §3-2 |
| `secularDemand` | 고령화·AI·전력·방산 등 장기 불변 수요 | §3 '불변' |
| `exportGlobal` | 수출·해외매출 확대와 직접 연결 | §3 '수출' |
| `shareholderReturn` | 배당·자사주매입·소각 | §3-4 |
| `priceAlreadyMoved` | 이미 일어난 주가 등락의 사후 보도 | 실측 손실 패턴 |
| `beneficiary` | `SELLER` / `BUYER` / `NEITHER` — 매출이 생기는 쪽이 대상 종목인가 | #860 |
| `riskFlag` | `NONE`/`DILUTION`/`GOVERNANCE`/`DELISTING`/`BLOCKDEAL`/`LITIGATION`/`LOSS` | §3-3, §3-5 |
| `why` | 한국어 한 문장(감사·설명용) | — |

### 2.2 판정 규칙 (`CatalystQualifier`)

평가 순서대로, 하나라도 걸리면 **탈락**하고 그 사유를 반환한다(선순위 사유만 기록).

| # | 조건 | 결과 | 근거 |
|---|------|------|------|
| 1 | `riskFlag != NONE` | **차단** | §3-3, §3-5 — **긍정 사실이 함께 있어도 상쇄 불가** |
| 2 | `priceAlreadyMoved` | **차단** | 선반영 진입 = 실측 손실 경로 |
| 3 | `!confirmed` | **차단**(후보 등록 불가) | §3-1 '구체 촉매' |
| 4 | `!materialAmount` | **차단** | §3-1 — 규모 없는 촉매는 촉매가 아님 |
| 5 | `isTransaction && beneficiary != SELLER` | **차단** | #860 — 돈 쓰는 쪽은 호재 아님 |
| — | 그 외 | **통과** | |

통과분의 **우선순위 점수**(게이트 아님, 정렬용):
`score = (recurring?1:0) + (secularDemand?1:0) + (exportGlobal?1:0) + (shareholderReturn?1:0)`
— §3 '불변 × 수출' 프레임에 가까운 촉매를 먼저 본다.

### 2.3 실측 검증 (설계 확정 근거)

현재 통과 중인 EVENT+S4↑ 기사 40건 무작위 표본에 2.2 규칙 적용:

```
통과 7건 / 40건 = 17.5%
전체 기사 대비 환산: 37% × 7/40 ≈ 6.5%   ← 목표 <10% 충족
```

탈락 사유 분포:

| 사유 | 건수 | 대표 예 |
|------|------|---------|
| 규모 미제시 | 12 | 발행어음 특판, MOU, 산학협력, 어묵면 출시 |
| 미확정(전망·목표가) | 9 | `"목표가↑"-iM`, 비전 발표 예정, 질환인식 캠페인 |
| 선반영·사후보도 | 7 | 삼성전자 100조 사후기사 4건, `[특징주]`, "680% 뛴" |
| 수혜 주체 아님 | 5 | LG엔솔이 리튬을 "공급받기로"(=구매자) |

### 2.4 실측에서 발견한 결함 2개 — 본 설계의 교정 사항

**교정 A — `beneficiary` 를 거래 기사에만 적용.**
표본에서 같은 LG에너지솔루션 3분기 실적인데 `"매출 9.6조 분기 최대"` 는 통과,
`"영업익 7560억 분기 최대"` 는 *수혜주체 아님* 으로 탈락했다. 실적 발표·규제 승인 같은
**비(非)거래 기사엔 수혜 주체 개념이 없다** — 적용하면 같은 사실이 제목 표현에 따라
갈린다. → `isTransaction` 필드를 두고 규칙 5를 그때만 평가한다.

**교정 B — `materialAmount` 를 '아무 숫자'에서 좁힌다.**
통과 7건 중 2건이 허위양성이었다: `GS건설 견본주택 오픈…84㎡ 499가구`,
`한화생명 설계사 수 4만명 육박`. 숫자는 있으나 **실적 촉매가 아니다**.
→ 정의를 "매출·이익에 직결되는 금액 또는 수량"으로 하고, 프롬프트에 부정 예시를
명시한다: 가구수·인원수·회원수·점포수·참가자수·면적 등 **매출 금액이 아닌 수치는 false**.
또한 단순 개점·오픈·착수·참가·수상 이벤트는 금액이 적시돼도 `confirmed` 는 true지만
`materialAmount` 는 false로 둔다(그 숫자가 이 분기 매출이 아니므로).

### 2.5 저장 (스키마)

`stock_news` 에 `facts CLOB`(JSON 원문) 1개 컬럼 추가. `SchemaInitializer` 에
멱등 DDL 블록으로 넣는다(27번째 블록).

- `sentiment`/`kind`/`targets` 는 **그대로 유지** — 뉴스 화면·`NewsFadeDetector` 호환.
- 매수 결정만 `facts` 기반으로 바꾼다. 즉 본 변경은 **표시와 결정을 분리**한다.
- `facts` 가 null(추출 실패·구버전 행)이면 **매수 불가**로 둔다 — 아래 2.6.

### 2.6 실패 시 거동: fail-closed

LLM 호출 실패·JSON 파싱 실패·`facts` 부재 → **촉매 자격 없음(매수 안 함)**.

근거: 실거래 자금이고, 원칙 §3 은 "통과 기업만 후보로"이며 §0 은 주 1회 거래를
전제한다. **미진입은 원칙에 부합하는 결과이고, 오진입은 아니다.** 기존 급등 필터
(`PriceExtension`)는 측정 불가 시 fail-open 이었는데 이는 성질이 다르다 — 그쪽은
"위험 신호를 못 봤다"이고, 이쪽은 "매수 근거가 없다"이다. 근거 부재는 보수적으로 막는다.

> 부작용: LLM 장애 시 신규 매수가 멈춘다. 의도된 거동이며, 보유 포지션의 손절·추적손절은
> 뉴스와 무관하게 동작하므로 리스크 관리에는 영향 없다.

## 3. 함수 등재 (Function Registry)

| 함수 | 파일 | 책임 | I/O | 비고 |
|------|------|------|-----|------|
| `record NewsFacts(...)` | `news/NewsFacts.java` (신규) | 2.1 사실 11필드 보유 | 순수 | 불변 record |
| `NewsFacts.from(JsonNode)` | 동일 | LLM JSON → record, 누락 필드 보수적 기본값 | 순수 | 실패 시 null |
| `NewsFacts.toJson()` | 동일 | 저장용 JSON 직렬화 | 순수 | |
| `CatalystQualifier.qualify(NewsFacts)` | `autotrade/CatalystQualifier.java` (신규) | 2.2 규칙 평가 | 순수 | → `Verdict` · **`fn-qualify.md`** |
| `CatalystQualifier.score(NewsFacts)` | 동일 | 2.2 우선순위 점수 0~4 | 순수 | 게이트 아님 |
| `record Verdict(boolean pass, String reason, int score)` | 동일 | 판정 결과 + 사유 | 순수 | reason 은 로그·화면용 한국어 |
| `NewsClassifier.classify(String)` | 기존 수정 | 응답에 `facts` 추가 파싱 | I/O | 반환형에 facts 추가 |
| `NewsClassifier.parse(String)` | 기존 수정 | `facts` 노드 파싱 | 순수 | |
| `StockNewsMapper.updateAnalyzed(...)` | 기존 수정 | `facts` 컬럼 추가 저장 | I/O | 파라미터 1개 추가 |
| `StockNewsMapper.findRecentEvents(...)` | 기존 수정 | `facts` 조회 포함 | I/O | SELECT 목록만 변경 |
| `CandidateDiscoveryService.addNewCandidates()` | 기존 수정 | `level>=4` → `level>=4 && qualify().pass()` | I/O | 탈락 사유 로그 |

`CatalystQualifier.qualify` 는 분기·리스크 경로이므로 별도 함수 설계서
`fn-qualify.md` 를 둔다.

## 4. 인수조건 (Acceptance)

1. 동일 기사 모집단에서 1단 통과율 **10% 미만** (실측 6.5%).
2. `riskFlag != NONE` 기사는 긍정 사실이 함께 있어도 **100% 차단**.
3. 판정 사유가 기사별로 저장·조회 가능(`facts` + `Verdict.reason` 로그).
4. 기존 `sentiment`/`kind`/`targets` 와 뉴스 화면·`NewsFadeDetector` 동작 **불변**.
5. `facts` 추출 실패 시 매수하지 않는다(fail-closed).
6. 교정 A: 비거래 기사(`isTransaction=false`)는 `beneficiary` 로 탈락하지 않는다.
7. 교정 B: `499가구`·`설계사 4만명` 류 비(非)실적 수치는 `materialAmount=false`.

## 5. 테스트 계획

`CatalystQualifierTest` (순수 단위, LLM 불필요):

- 규칙 1: `riskFlag=DILUTION` + `confirmed·materialAmount·SELLER` 전부 양호 → **탈락**(상쇄 불가)
- 규칙 2: `priceAlreadyMoved=true` → 탈락
- 규칙 3: `confirmed=false` → 탈락
- 규칙 4: `materialAmount=false` → 탈락
- 규칙 5(교정 A): `isTransaction=true, beneficiary=BUYER` → 탈락 /
  `isTransaction=false, beneficiary=NEITHER` → **통과**
- 통과 케이스: 확정 + 실적금액 + SELLER + 리스크 없음 → 통과
- `score`: 4개 가점 전부 → 4, 전무 → 0
- `qualify(null)` → 탈락(fail-closed)

`NewsClassifierTest`: `facts` 누락 응답 → `NewsFacts` null, 기존 level 파싱은 유지.
`CandidateDiscoveryServiceTest`: 자격 미달 기사는 후보 등록 안 됨, 자격 통과만 등록.

## 6. 리스크 / 되돌리기

| 리스크 | 완화 |
|--------|------|
| 통과율이 과하게 낮아져 거래가 거의 멈춤 | 원칙 §0 은 **주 1회 거래**를 전제 — 과소거래는 원칙 부합. 1주 관측 후 규칙 4(금액 제시)만 완화 검토 |
| LLM 이 `materialAmount` 를 과대 판정 | 프롬프트에 부정 예시 명시(2.4 교정 B) + 통과분 로그를 주 1회 샘플 점검 |
| 프롬프트 토큰 증가로 비용·지연 상승 | 기존 1회 호출에 필드만 추가(호출 수 불변) |
| 기존 행에 `facts` 없음 | fail-closed — 신규 수집분부터 자연히 채워짐. 백필 불필요 |

되돌리기: `CandidateDiscoveryService` 의 자격 조건 한 줄 제거로 즉시 이전 동작 복귀
(`facts` 컬럼·추출은 남겨도 무해).

## 7. 범위 밖 (Out of Scope)

- **NEWS_FADED 매도** — 원칙 §4 에 없는 매도 규칙이고 현재 손실의 주 경로다. 기사 만료
  ≠ 투자논거 무효이므로 **이벤트 단위 추적**으로 바꿔야 한다. 별도 이슈.
- **분당 매매 vs 원칙 §0/§6 주 1회 주기** 정합. 별도 이슈.
- 2단 종목 게이트(§3-2~3-7) 강화 — 기존 `FundamentalScore` 유지.
