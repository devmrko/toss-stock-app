# 레퍼런스: 뉴스-독립 후보 유지 (#828)

> **문서 성격**: 구현된 동작·설정 사양(Diátaxis Reference). 설계 의도/근거는
> `docs/design/828-news-independent-retention/README.md`.
> **구현 커밋 기준**: 2026-10-05 · **패키지**: `com.cloudhandson.tossstock.autotrade`
> **구현 파일**: `CandidateDiscoveryService.java`, `AutoTradeScheduler.java`
> **테스트**: `CandidateDiscoveryServiceTest`(#828 섹션 6케이스)

## 1. 동작 요약

호재(뉴스 S4↑)가 소멸한 매수 후보를 **즉시 해제하지 않고**, 아래 3조건을 **모두** 만족하면
후보 풀에 유지하고 매수 스캔 대상으로도 계속 둔다.

1. 등록(`auto_trade_candidate.created_at`) 후 `auto-trade.candidate-max-retention-days` **이내**
2. 저평가 — `ValuationChecker.isUndervalued(valuation, maxPer, maxPbr)`
3. 상대강세 — `RelativeStrengthChecker.isRelativelyStrong(종목수익률, 지수수익률)`
   (윈도우 `relative-strength-window-days`, 지수는 KR `069500` / US `SPY`)

하나라도 미달이면 기존과 동일하게 해제/스킵한다(회귀 없음). 호재가 **살아있는** 후보는 이
판정을 전혀 거치지 않으므로 보유기간 상한의 영향도 받지 않는다.

## 2. 함수

### `CandidateDiscoveryService.retainDespiteNewsFade(AutoTradeCandidate c) → boolean`
- 가시성 **package-private** — 같은 패키지의 `AutoTradeScheduler`가 매수 스캔에서 재사용한다
  (후보 풀 유지/매수 스캔 두 경로가 동일 판정을 쓰도록 단일 지점에 둠).
- 평가 순서는 비용이 싼 순(보유기간 → 일봉 DB → 밸류에이션 외부 API). AND 조건이라 결과는
  순서와 무관하며, 네이버/야후 호출 수를 줄이기 위한 선택이다(스캔이 1분 주기).
- **fail-closed**: `created_at`이 `null`, 일봉 2건 미만(상대강도 산출 불가), 밸류에이션 조회
  실패(`null`)·PER/PBR 누락·음수면 모두 `false`(= 유지하지 않음 → 해제/스킵).

### `CandidateDiscoveryService.removeFadedCandidates()` (private, 15분 주기)
- 활성 후보 중 `hasNewsFaded == true` 인 것만 `retainDespiteNewsFade`로 재판정 →
  `false`면 `deactivate`, `true`면 유지(로그: `후보 유지(호재 소멸이나 저평가+상대강세)`).

### `AutoTradeScheduler.scanCandidates(int openSlots)` (private, 1분 주기)
- 기존 `hasNewsFaded → continue` 가 `hasNewsFaded && !retainDespiteNewsFade(c) → continue` 로 변경.
- 예외 통과 시 이후 게이트(인기 → 밸류/촉매 → 펀더멘털 → 가격)는 **그대로** 적용된다 —
  이번 변경은 "호재 소멸" 게이트 하나에만 OR-예외를 추가한 것이고, 다른 게이트는 무관.

## 3. 설정

| 키 | 환경변수 | 기본값 | 의미 |
|----|----------|--------|------|
| `auto-trade.candidate-max-retention-days` | `AUTO_TRADE_CANDIDATE_MAX_RETENTION_DAYS` | `30` | 호재 소멸 후 밸류+추세로 후보를 붙잡아 둘 수 있는 최대 일수(등록일 기준). 초과 시 조건 무관 해제 |

재사용 설정: `max-per`, `max-pbr`, `relative-strength-window-days`.

## 4. 스키마

변경 없음 — 기존 `auto_trade_candidate.created_at` 사용.
