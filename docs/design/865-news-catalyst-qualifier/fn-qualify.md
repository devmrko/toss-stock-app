# 함수 설계서: `CatalystQualifier.qualify` (#865)

> **부모 설계서**: ./README.md · **상태**: Approved
> **작성**: [AI] Architect · **구현**: `autotrade/CatalystQualifier.java:qualify` ·
> **테스트**: `src/test/java/com/cloudhandson/tossstock/autotrade/CatalystQualifierTest.java`

## 1. 시그니처
```java
public static Verdict qualify(NewsFacts f)

public record Verdict(boolean pass, String reason, int score) {}
```

## 2. 책임 (단일 책임, 1줄)
추출된 뉴스 사실 1건이 **투자원칙 §3 기준의 매수 촉매 자격**을 갖추는지 판정한다.

## 3. 입력
| 파라미터 | 타입 | 제약/검증 | 설명 |
|----------|------|-----------|------|
| `f` | `NewsFacts` | **null 허용** (추출 실패 신호) | 기사 1건에서 뽑은 사실 11필드 |

`NewsFacts` 의 `boolean` 필드는 primitive 가 아니라 **`Boolean`**(3-상태: true/false/미상)으로
둔다 — LLM 이 필드를 누락했을 때 `false` 와 "모름"을 구분해야 하기 때문이다. `qualify` 는
**미상(null)을 보수적으로** 다룬다(§6 표).

## 4. 출력
- **반환**: `Verdict` — `pass`(자격 여부), `reason`(한국어 사유, 통과 시 `"자격 충족"`),
  `score`(0~4 우선순위 점수).
- **부수효과**: 없음 — **순수 함수**. 로깅·DB·시각 조회 전부 하지 않는다.

## 5. 동작 / 알고리즘

```
0. f == null                                  → 탈락 "사실 추출 실패"   (null 가드)
1. riskFlag != NONE                           → 탈락 "리스크 이벤트(<flag>)"
2. priceAlreadyMoved == TRUE                  → 탈락 "선반영·사후보도"
3. confirmed != TRUE                          → 탈락 "미확정(전망·추측)"
4. materialAmount != TRUE                     → 탈락 "규모 미제시"
5. isTransaction == TRUE && beneficiary != SELLER → 탈락 "수혜 주체 아님(<beneficiary>)"
-. 그 외                                      → 통과 "자격 충족", score = §5.1
```

평가는 **위 순서대로 단락(short-circuit)** 한다 — 첫 번째 걸린 사유만 반환한다. 순서는
심각도순이다: 리스크(원금 손실) > 선반영(실측 손실 경로) > 미확정 > 규모 > 수혜주체.

### 5.1 점수
```
score = count(recurring == TRUE, secularDemand == TRUE,
              exportGlobal == TRUE, shareholderReturn == TRUE)
```
탈락 시에도 계산해 반환한다(사후 분석용). **게이트에 쓰지 않는다** — §3 '불변 × 수출'은
우선순위 기준이지 자격 기준이 아니다(원칙 문서는 "대부분 YES"를 요구하고 전부를 요구하지 않음).

### 5.2 `level` 과의 관계
`qualify` 는 `level`(S1~S5)을 **보지 않는다**. 호출부(`CandidateDiscoveryService`)가
기존 `level >= 4` 와 **AND** 로 결합한다. 등급은 표시·호환용으로 남기고 자격은 사실로
판정한다(README §2.5 "표시와 결정의 분리").

## 6. 에러 & 실패 모드
| 조건 | 처리 | 반환 |
|------|------|------|
| `f == null` (LLM 실패·파싱 실패·구버전 행) | **fail-closed** | `Verdict(false, "사실 추출 실패", 0)` |
| `riskFlag == null` | `NONE` 으로 간주(리스크 미검출 ≠ 리스크 존재) | 다음 규칙으로 진행 |
| `confirmed == null` | 미확정으로 간주 → 탈락 | `"미확정(전망·추측)"` |
| `materialAmount == null` | 미제시로 간주 → 탈락 | `"규모 미제시"` |
| `priceAlreadyMoved == null` | `false` 로 간주(차단 근거이므로 — 아래 비대칭 규칙) | 다음 규칙으로 진행 |
| `isTransaction == null` | 거래 아님으로 간주 → 규칙 6 건너뜀 | 교정 A 취지 유지 |
| `beneficiary == null` 이고 `isTransaction == TRUE` | `SELLER` 아님 → 탈락 | `"수혜 주체 아님(미상)"` |

> **null 처리의 비대칭 규칙**(의도된 것):
> - **매수 근거** 필드(`confirmed`, `materialAmount`, 그리고 거래 기사의 `beneficiary`)의
>   null → **탈락**. 근거 부재는 매수 금지다.
> - **차단 근거** 필드(`riskFlag`, `priceAlreadyMoved`)의 null → **차단하지 않음**.
>   null 을 "리스크 있음"으로 읽으면 추출이 조금만 불안해도 전 기사가 막힌다.
>
> 즉 **"모름"은 매수 쪽으로는 불리하게, 차단 쪽으로는 유리하게** 읽는다 — 어느 쪽이든
> 결과는 "안 산다"로 수렴하지 않고, 근거가 분명한 것만 통과시킨다. `facts` 자체가 null 인
> 경우(추출 전면 실패)는 규칙 1에서 이미 fail-closed 로 막는다.

예외는 던지지 않는다 — 스케줄러 루프 안에서 호출되므로 한 기사의 이상이 전체 수집을
멈추게 하면 안 된다.

## 7. 엣지케이스
- **전 필드 null**(객체는 있으나 내용이 비었음) → 규칙 1·2는 차단 근거라 통과,
  규칙 3에서 `confirmed == null` 로 걸려 `"미확정(전망·추측)"` 탈락. 테스트로 사유 고정.
- **리스크 + 강한 호재 동시**(예: "대규모 수주 공시, 동시에 유상증자 결정") → 규칙 2에서
  차단. **인수조건 2번이 요구하는 동작**이며, 점수가 높아도 통과시키지 않는다.
- `beneficiary` 가 `SELLER` 지만 `isTransaction=false` (실적 발표) → 규칙 6 미적용, 통과.
- `score` 가 0이어도 자격 자체는 통과 가능(점수는 게이트가 아니므로).
- 동시성: 상태 없는 `static` 순수 함수 — 스레드 안전.

## 8. 복잡도 / 성능
- 시간 `O(1)`(필드 7개 비교), 공간 `O(1)`.
- 호출 빈도: `discovery-cron` 기본 15분 주기 × 최근 24시간 EVENT 기사 수(수백 건).
  시세 폴링 루프 **밖**이다. 성능 고려 불필요.

## 9. 의존성
- 호출: 없음(순수). `NewsFacts` 레코드만 참조.
- 외부 API·설정 키: **없음** — 임계값이 없는 규칙 게이트다. 설정화하면 우회 경로가
  생기므로(§6 "게이트 우회 금지") 의도적으로 상수 규칙으로 둔다.
- 호출부: `CandidateDiscoveryService.addNewCandidates()`.

## 10. 테스트 케이스
- [ ] 정상 통과: `confirmed=T, materialAmount=T, priceAlreadyMoved=F, riskFlag=NONE,`
      `isTransaction=T, beneficiary=SELLER` → `pass=true, reason="자격 충족"`
- [ ] 규칙 1: 위와 동일 + `riskFlag=DILUTION` → `pass=false`, 사유에 `DILUTION` 포함
      **(인수조건 2 — 긍정 사실 상쇄 불가)**
- [ ] 규칙 2: `priceAlreadyMoved=T` → 탈락 `"선반영·사후보도"`
- [ ] 규칙 3: `confirmed=F` → 탈락 `"미확정(전망·추측)"`
- [ ] 규칙 4: `materialAmount=F` → 탈락 `"규모 미제시"`
- [ ] 규칙 5 / 교정 A-1: `isTransaction=T, beneficiary=BUYER` → 탈락
- [ ] 규칙 5 / 교정 A-2: `isTransaction=F, beneficiary=NEITHER` → **통과**
      (실적 발표 기사가 수혜주체로 탈락하지 않을 것 — **인수조건 6**)
- [ ] 우선순위: `riskFlag=DILUTION` + `priceAlreadyMoved=T` → 사유는 리스크 쪽
- [ ] 점수: 4개 가점 전부 `T` → `score=4`; 전부 `F` → `score=0`; 탈락건도 점수 반환
- [ ] 실패: `qualify(null)` → `pass=false, reason="사실 추출 실패", score=0` **(인수조건 5)**
- [ ] 엣지: 전 필드 null → 탈락 `"미확정(전망·추측)"`(사유 문자열 고정)
- [ ] 엣지(비대칭 규칙): `riskFlag=null` 은 차단하지 않음 — 나머지 양호 시 **통과**
- [ ] 엣지(비대칭 규칙): `priceAlreadyMoved=null` 은 차단하지 않음 — 나머지 양호 시 **통과**
- [ ] 엣지(비대칭 규칙): `confirmed=null` / `materialAmount=null` 은 각각 **탈락**

## 11. 추적성
- 인수조건: #865 의 2(리스크 100% 차단), 5(fail-closed), 6(교정 A), 그리고
  1(통과율 10% 미만)의 판정 본체.
- 원칙 근거: `investment-principles.md` §3-1(구체 촉매), §3-3(희석), §3-5(분쟁·소송).
- 관련 ADR: 없음(되돌리기 쉬움 — README §6).
