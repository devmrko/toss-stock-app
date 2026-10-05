# 설계서: 뉴스-독립 후보 유지 — 밸류에이션+상대강도 기반 (#828)

> **상태**: Draft
> **작성**: [AI] Architect · **최종수정**: 2026-10-05
> **추적성** — Redmine: #828 · 관련: #808(모멘텀 엔진), 메모리 `exit-signal-macro-micro-theme-rotation-ideas.md`(2026-10-02) · 관련 ADR: 없음
> · 구현 파일(예정): `CandidateDiscoveryService.java`, `AutoTradeScheduler.java`(기존 파일 수정, 신규 클래스 없음)
> · 테스트(예정): `CandidateDiscoveryServiceTest.java`에 케이스 추가

## 1. 목적 (Why)
`CandidateDiscoveryService.removeFadedCandidates`가 뉴스가 S4 밑으로 떨어지면 **가격·밸류에이션과 무관하게 즉시 후보를 해제**하는 문제를 고친다. 실사례(2026-10-05): SMCI(슈퍼마이크로) — PER 13.40/PBR 2.80로 저평가, 9/14~10/1 2.5주간 +12% 꾸준히 우상향했는데, "Vera Rubin NVL72 출하" 뉴스의 TTL이 끝나자 가격 추세와 무관하게 `active=0`으로 자동해제됨.

목표(1줄): "뉴스 노출 주기가 끝나도, 밸류에이션과 추세가 여전히 좋으면 후보를 더 붙잡아둔다."

## 2. 범위 (Scope)
- **포함**:
  - 뉴스 소멸 시 즉시 해제하지 않고, **저평가(cheap) AND 상대강세(trending)**면 후보 유지.
  - 무기한 유지 방지용 **최대 보유기간 상한**(뉴스 유무와 무관하게).
  - #808 매수 스캔(`scanCandidates`)의 "호재 소멸 시 스킵" 조건에도 동일한 OR-예외 적용 — 후보풀에만 남고 실제로 못 사면 의미 없음.
- **제외 (out of scope)**:
  - **완전히 새로운 종목을 뉴스 없이 발굴하는 것(블라인드 유니버스 스캔)** — PER/PBR이 DB에 없고 네이버/야후에서 종목별 실시간 조회하는 구조라, KR 3700+·US 전체를 매번 조회하면 API 레이트리밋을 넘음. **한 번 뉴스로 발견된 적 있는 종목을, 뉴스 소멸 후에도 더 오래 추적**하는 것까지만 v1 범위.
  - 섹터/테마 간 비교("GPU 대비 메모리가 덜 재평가됐다" 같은 교차 비교) — 메모리 파일에 적힌 더 큰 아이디어의 일부지만, 이번 이슈는 "단일 종목의 밸류+추세 유지"만 다룸. 교차비교는 후속 이슈.

## 3. 인수조건 (Acceptance Criteria)
- [ ] 뉴스가 소멸했지만 저평가(PER/PBR 기준 통과) + 상대강세(지수 대비 초과수익)인 후보는 `removeFadedCandidates`에서 해제되지 않는다.
- [ ] 뉴스가 소멸하고 저평가 또는 상대강세 중 하나라도 실패하면 기존대로 즉시 해제된다(회귀 없음).
- [ ] 후보가 등록된 지 `candidate-max-retention-days`(예: 30일)를 넘으면, 밸류에이션이 아무리 좋아도 해제된다(무기한 좀비 후보 방지).
- [ ] `AutoTradeScheduler.scanCandidates`도 동일한 "저평가+상대강세"면 호재 소멸을 무시하고 매수 스캔을 계속한다 — 후보풀 유지가 실제 매수 기회로 이어진다.

## 4. 컨텍스트 & 제약
- **API 레이트리밋 제약(핵심)**: `ValuationChecker`의 PER/PBR은 네이버(KR)/야후(US) 비공식 API에서 종목별 실시간 조회 — DB에 저장된 값이 아님. 그래서 "전체 유니버스를 매번 밸류에이션으로 스캔"은 불가능, 이미 후보였던(=한 번이라도 뉴스로 발견된) 종목만 재점검 대상으로 한정.
- **기존 재사용 자산**: `ValuationChecker.isUndervalued`(저평가), `RelativeStrengthChecker.isRelativelyStrong`+`pctReturn`(상대강세) — #808에 이미 있는 순수 함수 그대로 재사용, 신규 클래스 불필요.
- **패턴 일관성**: 이건 `CapitalReturnCatalystDetector`가 "비싸도 촉매 있으면 통과"로 밸류에이션 게이트에 OR-예외를 추가한 것과 똑같은 구조 — 이번엔 "밸류+추세 좋으면 뉴스소멸 게이트에 OR-예외"를 추가하는 것.

## 5. 아키텍처 개요
```
[CandidateDiscoveryService.removeFadedCandidates] (15분마다)
  for 활성후보 c:
    if 호재 안 소멸 → 유지(기존 그대로)
    else:
      if (created_at 이 max-retention-days 초과) → 해제(무조건)
      else if (저평가 AND 상대강세) → 유지(신규 예외 경로)
      else → 해제(기존 그대로)

[AutoTradeScheduler.scanCandidates] (1분마다)
  for 후보 c:
    if 호재 안 소멸 → 계속 진행(기존 그대로)
    else if (저평가 AND 상대강세) → 계속 진행(신규 예외 경로, 호재소멸이라도 매수 시도는 가능하게)
    else → skip(기존 그대로)
```
- **I/O ↔ 순수 로직 경계**: 밸류에이션 조회(I/O)는 기존처럼 스케줄러가 하고, "유지할지" 판정 자체는 순수 불린 조합(`cheap && trending`)이라 신규 복잡 함수 불필요 — §7에서 "단순"으로 분류.

## 6. 데이터 모델
- **스키마 변경 없음**. `auto_trade_candidate.created_at`(기존 컬럼)을 최대보유기간 계산에 그대로 사용.

## 7. 함수 명세 (Function Specs)

| 함수 | 책임(1줄) | 시그니처(잠정) | 입력 | 출력 | 에러/실패 | 복잡? |
|------|-----------|----------------|------|------|-----------|-------|
| `CandidateDiscoveryService.removeFadedCandidates` | 호재 소멸 후보 중 밸류+추세 예외 대상 제외하고 해제(기존 함수 수정) | `void removeFadedCandidates()` | - | - | 밸류에이션 조회 실패 시 안전 쪽(해제, 기존 fail-closed 유지) | 단순(기존 분기에 조건 추가) |
| `AutoTradeScheduler.scanCandidates` | 호재 소멸이어도 밸류+추세 예외면 매수 스캔 계속(기존 함수 수정) | `void scanCandidates(int openSlots)` | - | - | 동일 | 단순 |

> 신규 순수함수 없음 — `ValuationChecker.isUndervalued`/`RelativeStrengthChecker.isRelativelyStrong`를 그대로 재사용.

## 8. 흐름 / 알고리즘
1. `removeFadedCandidates`: 각 활성 후보에 대해 `hasNewsFaded`가 true일 때만 아래 추가 판정(기존엔 true면 바로 해제):
   a. `now - createdAt > maxRetentionDays` → 해제(밸류 무관, 상한선).
   b. 아니면 `ValuationChecker.isUndervalued(valuationClient.getValuation(...), maxPer, maxPbr)` **AND** `RelativeStrengthChecker.isRelativelyStrong(stockReturn, indexReturn)`(윈도우는 기존 `relativeStrengthWindowDays` 재사용) → 유지(해제 안 함).
   c. 둘 다 아니면 해제(기존과 동일).
2. `scanCandidates`: `newsFadeDetector.hasNewsFaded(c.getSymbol())`가 true일 때 기존엔 바로 `continue`였는데, 위 b와 동일한 조건을 만족하면 `continue` 하지 않고 이후 로직(인기·밸류·펀더멘털 게이트) 계속 진행.

## 9. 엣지케이스 & 에러 처리
- **밸류에이션/가격 조회 실패**: 외부 API(네이버/야후) 호출 실패 시 — 기존 `ValuationChecker`/`RelativeStrengthChecker`와 동일하게 **fail-closed**(판정 불가 → 유지 안 함 → 해제). 이미 "데이터 없으면 안전 쪽" 원칙과 일관.
- **최대보유기간 상한값(`candidate-max-retention-days`)**: 초기 추정치, 확정 아님 — §12.
- **호출 비용**: `removeFadedCandidates`가 15분마다 전체 활성 후보를 순회하는데, 호재 소멸한 후보에 대해서만 추가로 밸류에이션 API를 호출(기존에도 안 하던 추가 호출) — 활성 후보 수가 지금처럼 수십 개 수준이면 부담 적음, 수백 개로 늘면 재검토.
- **SMCI 재현 테스트**: 실사례 수치(PER 13.40/PBR 2.80, 9/14→10/1 +12%)를 테스트 픽스처로 사용.

## 10. 테스트 계획
- `CandidateDiscoveryServiceTest`에 추가:
  - 호재 소멸 + 저평가 + 상대강세 → 해제 안 됨(SMCI 실사례 수치)
  - 호재 소멸 + 저평가만(상대강세 아님) → 해제됨
  - 호재 소멸 + 상대강세만(저평가 아님) → 해제됨
  - 호재 소멸 + 둘 다 충족이지만 `created_at`이 상한 초과 → 해제됨
  - 밸류에이션 조회 실패(null) → 해제됨(fail-closed)
- `AutoTradeScheduler` 쪽은 기존에 전용 테스트 파일이 없는 패턴(README #808 참고) — 수동 검증 + 운영 로그로 확인.

## 11. 리스크 & 대안 검토
- **"완전 블라인드 발굴"을 당장 안 하는 이유**: §4에 적은 API 레이트리밋 제약이 본질적 — 이걸 풀려면 밸류에이션 데이터를 DB에 주기적으로 캐싱하는 별도 배치가 먼저 필요함(이번 이슈 범위 밖, 후속 이슈로 분리 추천).
- **AND vs OR(저평가/상대강세)**: AND를 선택 — "싸지만 추세는 하락중"이거나 "오르지만 이미 비쌈" 둘 다 보수적으로 걸러내는 쪽. OR로 하면 #808의 다른 게이트들과 중복 완화가 누적돼 판별력이 떨어질 위험.

## 12. 미해결 질문 (Open Questions)
- `candidate-max-retention-days` 확정값 없음 — 30일을 초기 제안(검증 전까지 비확정).
- 이 로직이 §2에서 제외한 "교차 섹터 비교"(테마로테이션)로 자연스럽게 이어질 수 있는데, 그건 별도 이슈로 남겨둠 — 메모리 파일 참고.
