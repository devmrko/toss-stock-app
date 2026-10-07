# 설계서: 손절 후 재진입 시 동일 테마(중복) 뉴스 판단 (#840)

> **상태**: Approved
> **작성**: [AI] Architect · **최종수정**: 2026-10-07
> **추적성** — Redmine: #840 · 관련 ADR: 없음
> · 구현 파일: `StockNewsMapper.java`/`.xml`(쿼리 추가), `AutoTradePositionMapper.java`/`.xml`
>   (쿼리 추가), `CandidateDiscoveryService.java`(테마 비교 함수 추가), `AutoTradeScheduler.java`
>   (게이트 추가) · 테스트: `CandidateDiscoveryServiceTest`, `AutoTradeSchedulerTest`(추가)

## 1. 목적 (Why)
#839(2026-10-07)로 "손절 직후 즉시 재진입"(066570, 7초 후 더 비싼 가격 재매수)은 막았지만,
사용자가 더 근본적인 문제를 지적: 10/6 최초매수 근거("AI 데이터센터 냉각 솔루션 공급 계약")와
10/7 재매수 근거("3분기 누적 영업이익 4조 첫 돌파")는 헤드라인이 다르지만 **본질적으로 같은
테마**(LG전자 AI/가전 호조 스토리)의 연장이다. #839의 시간 기반 쿨다운(30분)은 "다른 스토리인
새 호재"와 "같은 스토리의 다음 기사"를 구분하지 못한다 — 30분만 지나면 똑같이 재매수된다.

## 2. 범위 (Scope)
- **포함**: 손절(HARD_STOP/TRAIL_STOP)된 포지션의 재진입 평가 시, 새 트리거 뉴스의 테마 태그가
  **방금 손절된 포지션을 만든 뉴스의 테마 태그와 겹치고, 같은 거래일 내**라면 그날은 재진입을
  막는다(#839의 시간 쿨다운보다 더 센 게이트, #839 위에 추가로 적용).
- **테마 태그 재사용(신규 분류 없음)**: `stock_news.sentiment` CSV에 종목코드와 함께 이미
  섹터/테마 태그가 들어있음(예: `"066570:S5,전자부품:S4"` → `전자부품`). 기존 순수 함수
  `NewsSignals.aggregate(List<String>)`가 이미 이 CSV들을 key→레벨 맵으로 파싱 — 종목코드
  키(및 범용 `MARKET` 키)를 뺀 나머지 키를 "테마 태그"로 재사용한다. LLM 재분류·신규 컬럼 없음.
- **재사용 데이터**: `auto_trade_position`에 이미 있는 `entry_at`/`exit_at`으로 "손절된 포지션이
  살아있던 기간"을 정의하고, 그 기간에 `published_at`이 속하는 `stock_news`를 조회해 테마 태그를
  뽑는다(신규 컬럼 불필요).
- **제외 (out of scope)**: 테마 태그 유사도의 정교한 NLP 매칭(동의어·상위개념 등) — 정확히
  같은 태그 문자열 매칭만 한다(단순 교집합). 부분 매칭/유사매칭은 과설계로 보고 제외.

## 3. 인수조건 (Acceptance Criteria)
- [ ] 같은 거래일에 손절된 종목이, 그 손절 직전 보유 기간에 활성이었던 뉴스와 **같은 테마
      태그를 공유하는** 새 뉴스로 재매수 시도되면 그 틱엔 매수하지 않는다(해당 거래일 내 계속
      차단, 날짜 바뀌면 자동 해제).
- [ ] 테마 태그가 겹치지 않는(완전히 다른 성격의) 새 호재면 #839의 기존 30분 쿨다운만 적용되고,
      그 이후엔 정상 매수.
- [ ] 손절된 포지션의 보유 기간에 해당하는 뉴스를 찾을 수 없으면(데이터 부족) 이 추가 게이트는
      적용하지 않는다(#839의 기존 쿨다운만 적용 — fail-open, 과차단 방지).
- [ ] 066570 실사례(전자부품/IT 테마 중복)를 재현한 테스트로 차단 확인, 테마가 다른 가상
      사례로 정상 매수 확인.

## 4. 컨텍스트 & 제약
- 의존성: 없음(신규 외부 I/O 없음) — 로컬 DB(`stock_news`, `auto_trade_position`) 조회만 추가.
- 제약: 테마 태그 비교는 **문자열 완전 일치**만 본다 — "전자부품"과 "IT/인터넷"처럼 사람 눈에는
  연관돼 보여도 태그 문자열이 다르면 겹치지 않는 것으로 판단(오탐 방지 쪽으로 단순화, §11).
- 가정: `NewsFadeDetector`/`CandidateDiscoveryService`가 이미 쓰는 "활성 뉴스 상위 N건" 조회
  패턴을 그대로 재사용 — 성능상 새로운 부하 없음.

## 5. 아키텍처 개요
```
[AutoTradeScheduler.scanCandidates]
  recentlyExitedViaStop(symbol)?  ← #839(기존, 시간 쿨다운 30분)
     │ 쿨다운 끝났어도
     ▼
  candidateDiscovery.isSameThemeAsRecentStopExit(symbol, market)   ← 신규(#840)
     │
     ├─ StockNewsMapper.forSymbolBetween(symbol, exitedPosition.entryAt-24h, exitedPosition.exitAt)
     │    → 과거 테마 태그 집합 A (NewsSignals.aggregate로 추출, 종목코드/MARKET 제외)
     ├─ newsMapper.active(symbol, N) → 현재 활성 뉴스
     │    → 현재 테마 태그 집합 B (동일 추출)
     └─ A ∩ B 비어있지 않고, 손절이 오늘(같은 거래일)이면 → true(차단)
  → true면 continue(매수 보류, 그날 내내)
```
- I/O ↔ 순수 로직 경계: DB 조회(`forSymbolBetween`, `findLastStopExited`, `active`)는 I/O,
  태그 추출·교집합 판정은 `NewsSignals.aggregate` + `Set.retainAll` 같은 순수 연산.

## 6. 데이터 모델
변경 없음(신규 컬럼/테이블 없음) — 기존 `auto_trade_position.entry_at/exit_at`,
`stock_news.published_at/sentiment`만 재사용.

## 7. 함수 명세 (Function Specs)

| 함수 | 책임(1줄) | 시그니처 | 입력 | 출력 | 에러/실패 | 복잡? |
|------|-----------|----------|------|------|-----------|-------|
| `StockNewsMapper.forSymbolBetween` | 기간 내 해당 종목 타겟 뉴스 조회(만료 무관) | `List<StockNews> forSymbolBetween(@Param("symbol") String, @Param("from") LocalDateTime, @Param("to") LocalDateTime)` | 종목, 기간 | 뉴스 리스트 | 없음(단순 SELECT) | 단순 |
| `AutoTradePositionMapper.findLastStopExited` | 가장 최근 HARD_STOP/TRAIL_STOP 청산 포지션 1건 | `AutoTradePosition findLastStopExited(@Param("symbol") String)` | 종목 | 포지션(없으면 null) | 없음 | 단순 |
| `CandidateDiscoveryService.isSameThemeAsRecentStopExit` | 오늘 손절된 포지션과 테마 태그 겹치는지 판정 | `boolean isSameThemeAsRecentStopExit(String symbol, String market)` | 종목/시장 | boolean | 데이터 부족 시 false(fail-open, §3) | 단순(분기는 있으나 외부 I/O 없이 로컬 DB 조회+순수 집합연산) |
| `AutoTradeScheduler.scanCandidates`(수정) | 테마중복 재진입 게이트 추가 | 기존 시그니처 유지 | - | void | 기존과 동일 | 단순(분기 1개 추가) |

## 8. 흐름 / 알고리즘
1. `recentlyExitedViaStop`(기존 #839)이 먼저 걸리면 거기서 끝(짧은 안전 쿨다운).
2. 쿨다운을 통과했어도, `findLastStopExited(symbol)`로 가장 최근 손절 포지션을 가져와
   `exit_at`이 오늘(거래일 기준, `LocalDate.now()`와 동일)이 아니면 테마 비교 자체를 안 함
   (며칠 지난 손절까지 영구히 테마로 묶으면 과차단 — 당일로 한정).
3. 오늘 손절이면: 그 포지션의 `entry_at - 24시간`부터 `exit_at`까지 활성이었던 뉴스들의
   sentiment CSV를 모아 `NewsSignals.aggregate`로 테마 태그 집합 A를 만든다(종목코드 키,
   `MARKET` 키는 제외 — 진짜 "테마"만 남김).
4. 지금 이 후보를 매수하게 만든 현재 활성 뉴스(`newsMapper.active(symbol, 5)`, #832에서 쓰는
   동일 조회)로 테마 태그 집합 B를 만든다.
5. A와 B의 교집합이 비어있지 않으면 → 같은 스토리의 연장으로 보고 그날은 매수 보류.

## 9. 엣지케이스 & 에러 처리
- 손절 포지션의 보유기간에 테마 태그가 있는 뉴스를 못 찾음(예: #828 유지경로로 뉴스 없이
  산 경우) → 집합 A가 비어 있어 교집합도 항상 비어 있음 → 이 게이트는 통과(기존 30분
  쿨다운만 적용, fail-open).
- 당일 두 번째 손절 이후 세 번째 재진입 시도 → `findLastStopExited`는 "가장 최근" 1건만
  보므로 가장 최근 손절 기준으로 판단(이전 손절은 무시) — 의도된 단순화.
- 자정을 넘기면(거래일이 바뀌면) `exit_at`이 "오늘"이 아니게 되므로 자동으로 게이트가 풀림
  (별도 배치/정리 작업 불필요).

## 10. 테스트 계획
- `CandidateDiscoveryServiceTest`: (1) 손절 포지션 보유기간 뉴스와 현재 활성 뉴스가 같은
  테마 태그(예: "전자부품") 공유 + 오늘 손절 → true. (2) 테마 태그가 다름 → false.
  (3) 손절이 어제 → false(당일 한정). (4) 과거 뉴스 못 찾음 → false(fail-open).
- `AutoTradeSchedulerTest`: 066570류 시나리오(같은 테마) → `orderExecutor.buy` 호출 안 됨.
  다른 테마 시나리오 → 정상 매수(#839 쿨다운만 지났으면).

## 11. 리스크 & 대안 검토
- 대안(기각): LLM으로 "이 뉴스가 저 뉴스와 같은 스토리인가" 직접 판단 — 더 정교하지만 매
  재진입 평가마다 LLM 호출이 추가돼 레이턴시·비용·레이트리밋 부담. 기존 섹터/테마 태그
  문자열 매칭으로 상당 부분을 저비용으로 커버 가능하다고 판단, 되돌리기 쉬운 결정(부족하면
  나중에 LLM 유사도로 교체 가능) — ADR 불필요.
- 리스크: 태그 완전일치만 보므로 "전자부품"과 "IT/인터넷"처럼 사실상 같은 이야기인데 태그가
  달라 못 잡는 경우가 있을 수 있음(false negative) — 과차단보다 누락이 안전한 방향이라 의도적
  선택. 운영 데이터 쌓이며 필요하면 태그 동의어 그룹 도입 검토(후속 과제).

## 12. 미해결 질문 (Open Questions)
- 없음.
