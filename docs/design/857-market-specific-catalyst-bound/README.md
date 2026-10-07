# 설계서: 촉매 면제 밸류에이션 상한을 시장별로 분리 (#857, #855 회귀 수정)

> **상태**: Approved
> **작성**: [AI] Architect · **최종수정**: 2026-10-08
> **추적성** — Redmine: #857(회귀 대상 #855) · 구현: `ValuationChecker`, `AutoTradeScheduler` ·
>   테스트: `ValuationCheckerTest`

## 1. 목적 (Why)
#855(오늘 배포)에서 촉매 면제에 `PBR ≤ maxPbr×3 = 9` 상한을 걸었다. 그런데 미국 대형주는
자사주매입으로 자기자본(장부가)이 축소돼 **PBR이 구조적으로 높다** — 이 저장소 실측 기록
(`CapitalReturnCatalystDetector` 주석, 2026-10-02): "마이크론 PBR 11.65 — S5 뉴스 5건에도
밸류에이션+촉매 키워드 둘 다 탈락, 이후 실제로 +2.46% 상승해 기회비용 확인". 같은 주석에
"절대 PBR 상한(3.0)이 **사실상 미국주식 전면 차단**처럼 작동"이라고 적혀 있고, 그래서
영어 촉매 키워드를 추가해 **촉매 면제를 미국주식의 유일한 통로**로 열어둔 상태였다.

#855는 KR 2건(포스코퓨처엠 PER 392 / 삼성SDI PER 없음)만 보고 상한을 정하면서 미국 영향을
확인하지 않았고, 결과적으로 그 유일한 통로를 더 좁혔다. 실제 운영 데이터로도 US 주문 시도는
여태 0건이다(활성 US 후보는 현재 9개).

## 2. 범위 (Scope)
- **포함**: `withinCatalystBound`에 "PBR 상한 미적용" 모드 추가, 호출부에서 시장별로 분기.
  US는 PER 상한만 적용(PBR 무시), KR은 기존대로 PER+PBR 둘 다.
- **제외**: 미국주식의 다른 차단 요인(인기 게이트 ±2%, 슬롯 경쟁 구조) — 별건(§5 참고).

## 3. 인수조건 (Acceptance Criteria)
- [ ] US 종목: PER이 상한 이내면 PBR 값과 무관하게(높거나 null이거나 음수여도) 촉매 면제 허용.
- [ ] US 종목: PER이 상한 초과거나 PER이 없으면/음수면 면제 거부(기존 fail-closed 유지).
- [ ] KR 종목: 기존 동작 그대로(PER·PBR 둘 다 상한 이내여야 면제).
- [ ] 회귀 확인: 마이크론급(PER 정상·PBR 11.65) → US면 면제 허용, KR이면 거부.

## 4. 설계
`ValuationChecker.withinCatalystBound(v, perBound, pbrBound)`의 `pbrBound`가 **0 이하이면
PBR 상한 미적용**으로 해석한다(센티널). 호출부:
```java
double pbrBound = "US".equalsIgnoreCase(c.getMarket())
        ? 0            // 미국: 자사주매입으로 장부가 왜곡 — PBR 상한 미적용(PER만)
        : props.maxPbr() * props.catalystValuationMultiple();
```
PER은 시장 무관하게 적용 — 미국이라도 PER 200짜리(예: 일부 고성장주)까지 촉매로 면제해 주는
건 #855의 취지(이미 극단적으로 반영된 가격은 촉매로 정당화 못 함)에 어긋난다.

## 5. 엣지케이스
- US + PBR null/음수 → PBR 미적용이므로 통과(PER만 본다). 자사주매입 과다로 자본이 음수인
  경우가 실제로 있어 의도된 동작.
- KR + PBR null → 기존대로 거부(fail-closed).

## 6. 테스트 계획
`ValuationCheckerTest`: ①PBR 상한 미적용(pbrBound=0)일 때 PBR 11.65여도 PER 이내면 true,
②같은 값이라도 pbrBound=9면 false, ③pbrBound=0이어도 PER 초과면 false, ④pbrBound=0이고
PBR null이어도 PER 이내면 true.

## 7. 리스크
- 미국주식 매수가 실제로 열리면서 그동안 검증 안 된 경로(미국 체결·수수료·세금)가 처음
  실행될 수 있음. US 수수료 0.1%는 #847에 반영돼 있으나 **US 세금은 미확인(0으로 저장)** —
  #847 §9에 기록된 기존 gap이 그대로 노출된다. 첫 미국 체결 후 실측 대조 필요.
