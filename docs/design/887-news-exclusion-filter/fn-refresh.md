# 함수 설계서: `CandidateDiscoveryService.refresh` (#887 재작성)

> **부모 설계서**: ./README.md · **상태**: Draft
> **작성**: [AI] Architect · **구현**: `autotrade/CandidateDiscoveryService.java:refresh` · **테스트**: `CandidateDiscoveryServiceTest`

## 1. 시그니처

```java
@Scheduled(cron = "${auto-trade.discovery-cron:0 */15 * * * *}", zone = "Asia/Seoul")
public void refresh()
```

## 2. 책임 (단일 책임, 1줄)

스크리너 결과와 `auto_trade_candidate` 활성 목록을 **동기화**한다.

## 3. 입력

파라미터 없음. 암묵 입력:

| 출처 | 내용 | 실패 시 |
|---|---|---|
| `dailyMapper.screeningSnapshot(today−90d)` | 종목별 로컬 집계 | 예외 → 사이클 포기 |
| `newsRiskExclusion.excludedSymbols(now−7d)` | 리스크 배제 집합 | 예외 → 사이클 포기 |
| `candidateMapper.findActive()` | 현재 활성 후보 | 예외 → 사이클 포기 |
| `props` | `screen-top-n`, `screener-markets`, `max-extension-pct`, 유동성 하한 | — |

## 4. 출력

- **반환**: `void`.
- **부수효과**: `candidateMapper.insert` / `candidateMapper.deactivate`, 퍼널 로그 1줄.

## 5. 동작 / 알고리즘

```
1) snapshot = dailyMapper.screeningSnapshot(LocalDate.now().minusDays(90))
     ※ 90일: 20일 수익률 + 5일 저점 + 20일 평균거래대금에 필요한 21바를
       휴장일 포함해 안전하게 덮는 길이.

2) if (snapshot.isEmpty())
       log.warn("스크리너 스냅샷 0건 — 이번 사이클 포기(기존 후보 유지)")
       return                                  // ★ 비활성화하지 않는다
   ─ 조회 장애를 "전 종목 이탈"로 오인해 후보를 전멸시키는 것을 막는다.

3) excluded   = newsRiskExclusion.excludedSymbols(LocalDateTime.now().minusDays(riskExclusionDays))
   indexRet   = { "KR": index20dReturn(snapshot, "069500") }
   result     = UniverseScreener.screen(snapshot, excluded, indexRet, params())

4) log.info(퍼널 1줄)                            // AC8

5) 동기화
   active        = candidateMapper.findActive()
   selectedSyms  = result.selected().map(symbol).toSet()

   5-1) 비활성화 대상 = active 중
          · 노트가 구 게이트(`자동발견(` 접두사)            → 전부      (AC9)
          · 노트가 스크리너(`스크리너(` 접두사) && !selectedSyms.contains(symbol)
          · 그 외(수동 등록) → 건드리지 않음                           (AC10)
        for each → candidateMapper.deactivate(symbol)

   5-2) 등록 대상 = result.selected() 중 !candidateMapper.existsActive(symbol)
        for each → candidateMapper.insert(new AutoTradeCandidate(
                     symbol, market, UniverseScreener.noteOf(today, c), now))
```

**순서가 중요하다** — 비활성화를 먼저 하고 등록을 나중에 한다. 반대로 하면 같은 종목이
"스크리너 통과 + 구 노트 보유"일 때 등록 후 즉시 비활성화되어 후보가 사라진다.

### 제거되는 것

| 기존 메서드 | 처리 | 이유 |
|---|---|---|
| `addNewCandidates()` | **삭제** | 뉴스 기반 등록 — AC1 |
| `removeFadedCandidates()` | **삭제** | 기사 만료 기반 제거. 뉴스가 근거가 아니므로 무의미 |
| `requalifyCandidates()` | **삭제** | 역할이 5-1 에 흡수됨 |
| `disqualifyReason(note)` | **삭제** | 위와 함께 |

### 남는 것 (다른 호출부가 쓴다)

| 메서드 | 호출부 | 유지 이유 |
|---|---|---|
| `isSameThemeAsRecentStopExit` | `tick()` `SAME_THEME` 게이트(#840) | 손절 후 같은 테마 재진입 차단은 뉴스 트리거와 무관 |
| `retainDespiteNewsFade` | `tick()` `NEWS_FADED` 게이트 | **게이트가 삭제되므로 호출부가 사라진다** → 메서드도 삭제 |

## 6. 에러 & 실패 모드

| 조건 | 처리 | 반환/예외 |
|------|------|-----------|
| `screeningSnapshot` 예외 | catch → `log.error` → return | 기존 후보 유지 |
| `excludedSymbols` 예외 | catch → `log.error` → return | **등록 안 함**(fail-closed) |
| `findActive` 예외 | catch → `log.error` → return | — |
| 개별 `insert` 실패 | catch → `log.warn` → 다음 종목 계속 | 부분 성공 허용 |
| 개별 `deactivate` 실패 | catch → `log.warn` → 다음 종목 계속 | 다음 사이클에 재시도됨(멱등) |
| `069500` 바 부족 | `indexRet` 에 KR 없음 → 스크리너가 KR 전체 탈락 → `selected` 빈 목록 | 등록 0건, 비활성화는 **진행**(의도: 지수 데이터가 없으면 새로 사지 않는다) |

> 마지막 행은 `snapshot.isEmpty()` 와 다르게 처리한다 — 스냅샷이 비면 **조회 장애**이지만,
> 지수 바가 없는 것은 **판정 불가**다. 전자는 상태를 보존하고 후자는 보수적으로 후보를 비운다.

## 7. 엣지케이스

- **첫 배포 직후** — 활성 후보 전부가 `자동발견(` 노트이므로 1사이클에 모두 비활성화되고
  스크리너 상위 40 이 새로 등록된다(AC9). 보유 포지션은 영향받지 않는다.
- **보유 중인 종목이 스크리너를 통과하지 못함** — 후보에서 비활성화되지만 **포지션은 유지**된다.
  매도는 `HARD_STOP` / `TRAIL_STOP` / `RISK_EVENT` 만이 결정한다(원칙 §4).
- **같은 종목이 수동 + 스크리너 양쪽** — `existsActive` 가 참이므로 중복 등록되지 않고,
  노트가 수동이므로 비활성화도 되지 않는다. 수동이 우선한다.
- **`screen-top-n = 0`** — `selected` 가 비고, 스크리너 등록분이 전부 비활성화된다.
  의도된 킬스위치다(§11).
- **발굴과 틱의 경쟁** — 틱이 후보를 읽는 중 발굴이 비활성화할 수 있다. 틱은 읽은 스냅샷으로
  진행하고 다음 틱에 반영된다. `ALREADY_HELD` / 슬롯 상한이 중복매수를 막으므로 무해하다.

## 8. 복잡도 / 성능

- 시간: 스냅샷 쿼리 1회(228k 행 집계) + 배제 쿼리 1회 + `findActive` 1회
  + `existsActive` ≤ N 회 + insert/deactivate ≤ N + |active| 회.
- 호출 빈도: 15분(일 96회). **틱(1분) 과 분리돼 있다.**
- 외부 API: **0회.** 밸류에이션·재무 API 는 `tick()` 이 `POPULARITY` 통과분에만 호출한다
  (실측 중위 8종목). 발굴을 무겁게 만들지 않는 것이 이 분리의 목적이다.
