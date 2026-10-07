# 설계서: 재평가촉매 면제에 밸류에이션 상한 추가 (#855)

> **상태**: Approved
> **작성**: [AI] Architect · **최종수정**: 2026-10-08
> **추적성** — Redmine: #855 · 관련 ADR: 없음 · 선행: #838(급등 필터)
> · 구현 파일: `ValuationChecker.java`(판정 추가), `AutoTradeScheduler.java`(면제 조건 수정),
>   `AutoTradeProperties.java`(필드 추가), `application.yml` ·
>   테스트: `ValuationCheckerTest`, `AutoTradeSchedulerTest`

## 1. 목적 (Why)
실측(2026-10-08, 손실 6건 역추적): `rerateCatalyst`(수주/계약/증설/투자/배당/자사주/호실적
키워드 매칭)가 true면 **저평가 필터와 급등 필터를 둘 다 통째로 면제**한다. 그 결과:

| 종목 | PER | PBR | 저평가(30/3) | 촉매 | 결과 |
|------|-----|-----|--------------|------|------|
| 포스코퓨처엠 | **392.49** | 4.21 | ✗ | ✓ "양극재 6조 수주" | 면제 통과 → **-3.83%** |
| 삼성SDI | **N/A** | 1.84 | ✗ | ✓ 같은 6조 계약 | 면제 통과 → **-2.11%** |

일반적인 호재 판정 기준은 ①실체(매출액 대비 비중·연환산·마진·확실성)와 ②선반영 여부
(발표 전 상승폭·PER/PBR 부담·컨센서스 대비)를 본다. 현재 로직은 ①을 전혀 못 보고, ②도
PER/PBR을 **갖고 있으면서 면제 판단에 쓰지 않는다**. 1단계로 ②의 PER/PBR만이라도 면제의
상한으로 건다.

## 2. 범위 (Scope)
- **포함**: `rerateCatalyst` 면제를 "밸류에이션이 과열이 아닐 때"로 제한. 과열 기준은 기존
  저평가 기준(`max-per`/`max-pbr`)의 배수로 정의(새 임의 숫자 추가 대신 기존 설정에 연동).
  PER/PBR 데이터가 없거나 음수(적자)면 면제 불가(fail-closed — `ValuationChecker` 기존 철학과 동일).
- **제외 (out of scope)**: 계약금액 파싱·매출액 대비 비중 계산(#856 예정 — 재무데이터 연동 필요),
  구매자/판매자 구분(#857 예정 — 분류기 프롬프트), 급등 측정 시점 교정(별건, 일봉→실시간).

## 3. 인수조건 (Acceptance Criteria)
- [ ] PER 또는 PBR이 `max-per × catalyst-valuation-multiple` / `max-pbr × 같은 배수`를 넘으면
      `rerateCatalyst`가 true여도 면제되지 않는다(= 기존 저평가/급등 필터가 그대로 적용).
- [ ] PER/PBR이 null이거나 0 이하(적자)면 면제되지 않는다.
- [ ] 배수 이내면 기존과 동일하게 면제된다(동작 변경 없음).
- [ ] 실측 회귀: 포스코퓨처엠(PER 392) → 면제 거부, 삼성SDI(PER null) → 면제 거부,
      LG전자(PER 36.7, 배수 이내) → 면제 유지(이번 변경으로는 안 걸림 — 의도된 범위).
- [ ] 기존 테스트 전부 통과 + 신규 케이스(과열/데이터없음/정상 3종).

## 4. 컨텍스트 & 제약
- 의존성: 이미 조회 중인 `Valuation`(ValuationClient, 캐시됨 #836) 재사용 — **신규 I/O 없음**.
- 제약: 실자금 경로. 면제를 좁히는 방향이라 "더 안 사게" 되는 변경(위험 증가 아님).
- 가정: 배수 기본값 3.0(= PER 90 / PBR 9)은 **잠정값**. 저평가 기준(30/3)의 3배까지는 촉매로
  정당화 가능, 그 이상은 어떤 촉매 키워드도 정당화 못 한다는 선. 실측 2건(392, N/A)을 거르고
  정상 범위(36.7)는 통과시키는 선에서 잡았으나, 근거가 n=4라 운영하며 조정 대상(§11).

## 5. 아키텍처 개요
```
scanCandidates:
  valuation = getValuation(...)                       (기존)
  cheap = isUndervalued(valuation, maxPer, maxPbr)    (기존)
  rerateCatalyst = hasRecentCatalyst(symbol)          (기존)
  ──────────────── 신규 ────────────────
  catalystAllowed = rerateCatalyst
                    && ValuationChecker.withinCatalystBound(valuation,
                         maxPer * multiple, maxPbr * multiple)
  ──────────────────────────────────────
  if (priceMovePct >= extremeMovePct && !catalystAllowed) continue;   (기존 조건의 변수만 교체)
  if (!cheap && !catalystAllowed) continue;                            (동일)
```
I/O ↔ 순수: `withinCatalystBound`는 순수 판정 함수(`ValuationChecker`에 추가, 기존
`isUndervalued`와 같은 자리·같은 fail-closed 철학).

## 6. 데이터 모델
설정 1개 추가: `auto-trade.catalyst-valuation-multiple`(기본 3.0). DB 변경 없음.

## 7. 함수 명세 (Function Specs)

| 함수 | 책임 | 시그니처 | 복잡? |
|------|------|----------|-------|
| `ValuationChecker.withinCatalystBound` | 촉매 면제를 허용할 밸류에이션 범위인지(순수) | `static boolean withinCatalystBound(Valuation v, double perBound, double pbrBound)` | 단순 |
| `AutoTradeScheduler.scanCandidates`(수정) | 면제 변수를 `catalystAllowed`로 교체 | 시그니처 불변 | 단순 |

## 8. 흐름 / 알고리즘
`withinCatalystBound`: v가 null이거나 per/pbr이 null → false. per/pbr이 0 이하 → false.
그 외 `per <= perBound && pbr <= pbrBound`.
(= `isUndervalued`와 동일 구조, 임계값만 느슨한 상한을 받음.)

## 9. 엣지케이스 & 에러 처리
- PER만 없고 PBR은 있는 경우(삼성SDI) → false(면제 불가). PER 없이 "재평가"를 논할 수 없으므로
  보수적으로 막는다.
- 적자(PER 음수) → false. 적자 기업의 "재평가 촉매"는 이번 범위에서 판단 불가로 본다.
- ValuationClient 조회 실패로 v=null → false(면제 불가) — 기존 `cheap` 판정도 같은 경우
  false라 일관됨(둘 다 막히므로 매수 안 함 = 안전 방향).

## 10. 테스트 계획
- `ValuationCheckerTest`: 상한 이내 → true / PER 초과 → false / PBR 초과 → false /
  PER null → false / PER 음수 → false.
- `AutoTradeSchedulerTest`: 촉매 있고 PER 과열 → 매수 안 함(기존 "촉매 있으면 급등에도 매수"
  테스트와 짝). 촉매 있고 정상 밸류 → 기존대로 매수.

## 11. 리스크 & 대안 검토
- 대안(기각): 면제 자체를 완전 제거 — LG전자(PER 36.7)처럼 정상 밸류에이션의 실적 촉매까지
  막히므로 과하다. 상한을 두는 쪽이 범위가 좁고 되돌리기 쉽다.
- 대안(기각): 별도 절대 임계값(예: PER 100) 신설 — 또 하나의 근거 없는 숫자가 늘어난다.
  기존 설정의 배수로 묶으면 조정 포인트가 하나로 유지된다.
- 리스크: 배수 3.0은 n=4 기반 잠정값. 너무 빡빡하면 정상 촉매까지 막을 수 있어, 매수 보류 시
  로그로 남겨 사후 검증 가능하게 한다(§12).

## 12. 미해결 질문
- 배수 3.0의 적정성 — 보류된 종목이 이후 실제로 올랐는지 추적해 조정 필요.
- PER이 없는 성장주/적자기업을 영구 배제하는 게 맞는지 — PSR 등 대체 지표 도입은 후속 검토.
