# 설계서: 거래량-가격 괴리 기반 후보 발굴(조용한 매집 스크리너) (#834)

> **상태**: Draft
> **작성**: [AI] Architect · **최종수정**: 2026-10-07
> **추적성** — Redmine: #834 · 관련 ADR: 없음
> · 구현 파일: `VolumeAccumulationChecker.java`(신규), `VolumeAccumulationDiscoveryService.java`(신규),
>   `VolumeAccumulationStats.java`(신규 DTO), `DailyOhlcvMapper.java`/`.xml`(쿼리 추가),
>   `AutoTradeCandidate.java`(필드 추가), `AutoTradeCandidateMapper.java`/`.xml`(컬럼 추가),
>   `CandidateDiscoveryService.java`(분기 추가), `auto_trade.sql`(컬럼 추가) ·
>   테스트: `VolumeAccumulationCheckerTest`, `VolumeAccumulationDiscoveryServiceTest`,
>   `CandidateDiscoveryServiceTest`(추가)

## 1. 목적 (Why)
사용자 제안(2026-10-07): 와이코프 "효과 대비 결과(effort vs. result)" — 거래량(효과)이 크게 늘었는데
가격(결과)은 별로 안 움직인 종목은 조용한 매집(또는 분산) 신호일 수 있다. 현재 #808의 유일한 후보
발굴 경로(`CandidateDiscoveryService.addNewCandidates`)는 **뉴스가 터져야만** 후보가 생기므로,
아직 뉴스화되지 않은 "조용히 쌓이는" 종목은 구조적으로 영원히 후보가 될 수 없다(메모리
`exit-signal-macro-micro-theme-rotation-ideas.md`에 적어둔 "뉴스 없어도 후보가 있을 수 있다"는
문제의 또 다른 해법 — #828이 "후보 유지"를 다뤘다면, 이번은 "후보 발굴" 자체를 넓히는 것).

## 2. 범위 (Scope)
- **포함**: KR 유니버스 전체(3,745개, 일봉 보유 3,476개) 대상 일 1회 배치 스크리닝 → 조건
  만족 종목을 `auto_trade_candidate`에 `discovery_source='VOLUME_ACCUM'`로 등록. 이후 매수
  여부는 기존 #808 게이트(인기·저평가/촉매·펀더멘털)가 그대로 판단(이 설계는 **발굴만** 넓히고
  매수 기준은 안 건드림 — 리스크 추가 없음).
- **세션 중 실데이터로 확정한 조건**(전부 실측 검증, §11 참고):
  - 거래량비 = 최근 20거래일 평균거래량 ÷ 그 이전 20거래일 평균거래량(월평균 대 월평균) ≥ **2.0배**
    — 당일 대비 20일평균(기존 `PopularityChecker` 방식)은 하루짜리 이상치에 취약함을 실측 확인
    (조선내화: 당일기준 100.9배 vs 월평균기준 3.1배 — 같은 종목, 완전히 다른 결론).
  - 같은 20거래일 구간 가격변동 **0% ~ 3%**(상승 포함, 하락 제외) — 하락 포함시 상위권이
    전부 급락종목으로 채워짐을 실측 확인(의도한 "조용한 매집"과 반대 신호).
  - 거래대금(월평균거래량×현재가) ≥ **8억원** — 상위 100~200위(327억~834억) 요구는 이 전략의
    전제(아직 안 알려진 중소형주)와 모순돼 후보가 0건이 됨을 실측 확인. 8억원은 전체 3,472종목
    중 상위 약 37%로, 노이즈(초소형주)와 샘플수의 균형점.
  - ETF/펀드/스팩 제외는 **이름 패턴이 아니라 메타데이터**로: `universe.sector`가 "기타형
    번들"이면서 `ksic`(산업분류) 값이 없는 경우만 제외. "기타" 섹터라도 `ksic`가 채워진
    종목(311개, 예: 시알홀딩스/대동/전방/빙그레)은 실제 소형주라 제외 대상 아님 — 이름
    패턴(`KODEX`/`TIGER`/`ACE`/`PLUS`만 커버)이 RISE/KIWOOM 등 다른 운용사 상품을 놓치는 걸
    실측으로 확인.
- **제외 (out of scope)**:
  - US 시장 — `us_universe` 12,445종목 중 `daily_ohlcv` 보유는 48개뿐(그때그때 후보로 잡혔던
    종목만 임시 백필). 전종목 정기백필 인프라가 없어(#828 QA 때도 동일 제약 확인) 이번 범위
    밖. 후속 과제로 남김(§12).
  - 매집/분산 방향 판별 고도화(가격 위치·추세 맥락 결합) — 이번 v1은 "발굴 풀을 넓히는 것"만,
    방향 판별은 기존 #808 게이트(저평가/펀더멘털/상대강도)에 위임. 다음 피드백 사이클에서
    재검토.
  - `auto_trade_candidate.discovery_source`를 과거 레코드에 백필 — 신규 컬럼은 기존 행엔
    기본값 `'NEWS'`로 채워짐(실제로도 전부 뉴스발견이었으므로 정확).

## 3. 인수조건 (Acceptance Criteria)
- [ ] 일 1회(장마감 후) 배치가 KR 유니버스 전체를 1개 쿼리로 스크리닝하고, §2 조건을 만족하는
      종목 중 이미 활성 후보가 아닌 것만 `auto_trade_candidate`에 `discovery_source='VOLUME_ACCUM'`로
      등록한다.
- [ ] 등록된 후보의 `valuation_note`에 발굴 근거(거래량비/가격변동/거래대금)가 남는다(#808의
      "자동발견(뉴스제목)" 패턴과 동일한 자리, 같은 컬럼 재사용).
- [ ] `discovery_source='VOLUME_ACCUM'` 후보는 `NewsFadeDetector.hasNewsFaded`(뉴스 전용 판정)
      대상이 아니라, `candidate-max-retention-days`(기존 #828 상한, 기본 30일) 하나로만
      만료된다 — 뉴스가 원래 없는 후보를 "뉴스 없음=소멸"로 즉시 떨어뜨리던 버그를 방지.
- [ ] ETF/펀드/스팩은 `sector`+`ksic` 메타데이터 조합으로 제외되고, 실제 소형주(기타 섹터+ksic
      있음)는 제외되지 않는다.
- [ ] 기존 뉴스발견 후보(`discovery_source='NEWS'`)의 동작은 전혀 바뀌지 않는다(회귀 없음).
- [ ] 신규 순수 함수(`VolumeAccumulationChecker.isAccumulating`) 단위테스트 + 배치 쿼리
      통합테스트 + `CandidateDiscoveryService` 분기 테스트.

## 4. 컨텍스트 & 제약
- 의존성: 기존 `daily_ohlcv`(DailyCollector가 매 거래일 15:40 KST 갱신), `universe`(sector/ksic
  이미 존재, 스키마 변경 불필요).
- 제약: 유니버스 전체(3,476종목) 분석 쿼리이므로 **1개 쿼리로 일괄 처리**(N+1 금지) —
  `DailyOhlcvMapper.rangeStatsBatch`(#818)와 동일 패턴.
- 가정: `daily_ohlcv`가 그날 장마감 데이터로 갱신된 이후에만 유효 — 장마감 전 실행은 전날
  데이터 기준이라 당일 변동 미반영(치명적이지 않음 — 다음날 재평가됨).

## 5. 아키텍처 개요
```
[VolumeAccumulationDiscoveryService] (신규, 일 1회 @Scheduled)
        │
        ▼
  DailyOhlcvMapper.volumeAccumBatch(windowDays=20)   ← 유니버스 전체 1쿼리
        │ (symbol, volumeRatio, pctChange, turnover)
        ▼
  VolumeAccumulationChecker.isAccumulating(stats, 임계값들)   ← 순수 함수
        │ true인 것만
        ▼
  candidateMapper.existsActive(symbol)?  → 이미 활성이면 skip
        │ 아니면
        ▼
  candidateMapper.insert(discoverySource='VOLUME_ACCUM', valuationNote=근거문자열)


[CandidateDiscoveryService.removeFadedCandidates] (기존, 수정)
  for c in findActive():
    if c.discoverySource == 'VOLUME_ACCUM':
        withinRetentionWindow(c.createdAt) ? 유지 : 해제     ← #828 함수 재사용
    else (NEWS, 기본값):
        기존 로직 그대로(hasNewsFaded → retainDespiteNewsFade)
```
- I/O ↔ 순수 로직 경계: `volumeAccumBatch`(SQL, I/O)가 숫자만 모아오고, `isAccumulating`(순수
  함수)이 판정한다 — `rangeStatsBatch`+`RangeBoundChecker` 와 동일 경계.

## 6. 데이터 모델
- `auto_trade_candidate` 신규 컬럼: `discovery_source VARCHAR2(20) DEFAULT 'NEWS' NOT NULL`
  (멱등 `ALTER TABLE ... ADD`, `holding.sql`의 기존 마이그레이션 패턴 재사용). 값은 `'NEWS'` |
  `'VOLUME_ACCUM'`.
- 신규 DTO `VolumeAccumulationStats`(배치 쿼리 결과, `DailyRangeStats`와 동일한 역할):
  `symbol`, `name`, `volumeRatio`(double), `pctChange`(double), `turnover`(BigDecimal).
- `valuation_note` 포맷(VOLUME_ACCUM): `"자동발견(거래량누적, <시각>): 거래량비X.X배/20일수익률+Y.Y%/거래대금Z.Z억"`
  — 기존 NEWS 포맷("자동발견(헤드라인)")과 같은 컬럼, 다른 접두사로 구분.

## 7. 함수 명세 (Function Specs)

| 함수 | 책임(1줄) | 시그니처(잠정) | 입력 | 출력 | 에러/실패 | 복잡? |
|------|-----------|----------------|------|------|-----------|-------|
| `DailyOhlcvMapper.volumeAccumBatch` | KR 유니버스 전체 거래량비/가격변동/거래대금 일괄 조회(ETF/펀드 제외) | `List<VolumeAccumulationStats> volumeAccumBatch(@Param("windowDays") int windowDays)` | windowDays(=20) | 조건 충족 종목 리스트(SQL에서 이미 sector+ksic 필터 적용) | 데이터 42거래일 미달 종목은 결과에서 자동 제외(HAVING) | 복잡(외부 I/O·비자명 윈도우 집계, `rangeStatsBatch`와 동일 분류지만 선례상 별도 fn-doc 불필요 — §8에서 상세 기술) |
| `VolumeAccumulationChecker.isAccumulating` | 배치 결과값이 "조용한 매집" 조건을 만족하는지 순수 판정 | `static boolean isAccumulating(double volumeRatio, double pctChange, BigDecimal turnover, double ratioThreshold, double minPct, double maxPct, BigDecimal minTurnover)` | 배치 수치 + 임계값 | boolean | null turnover → false(fail-closed) | 단순 |
| `VolumeAccumulationDiscoveryService.refresh` | 배치 조회→판정→신규 후보 등록 오케스트레이션 | `void refresh()` | 없음(필드로 의존성 주입) | void | 개별 insert 실패는 로그만(다른 종목 등록은 계속) | 단순(분기 없음, 순수 위임) |
| `CandidateDiscoveryService.removeFadedCandidates`(수정) | discovery_source별로 다른 만료 규칙 적용 | 기존 시그니처 유지 | 없음 | void | 기존과 동일 | 단순(기존 if-분기에 1개 분기 추가, 상태기계 아님) |

## 8. 흐름 / 알고리즘
**배치 쿼리**(`volumeAccumBatch`, `rangeStatsBatch`와 동일 윈도우 기법):
```sql
WITH ranked AS (
  SELECT symbol, trade_date, close_p, volume,
         ROW_NUMBER() OVER (PARTITION BY symbol ORDER BY trade_date DESC) rn
  FROM daily_ohlcv WHERE REGEXP_LIKE(symbol, '^[0-9]{6}$')
),
agg AS (
  SELECT symbol,
         MAX(CASE WHEN rn=1 THEN close_p END) latest_close,
         MAX(CASE WHEN rn=windowDays+1 THEN close_p END) close_ago,
         AVG(CASE WHEN rn BETWEEN 1 AND windowDays THEN volume END) cur_avg_vol,
         AVG(CASE WHEN rn BETWEEN windowDays+2 AND windowDays*2+1 THEN volume END) prev_avg_vol,
         COUNT(*) n
  FROM ranked WHERE rn <= windowDays*2+1 GROUP BY symbol
)
SELECT a.symbol, u.name,
       a.cur_avg_vol/a.prev_avg_vol AS volume_ratio,
       (a.latest_close-a.close_ago)/a.close_ago*100 AS pct_change,
       a.cur_avg_vol*a.latest_close AS turnover
FROM agg a JOIN universe u ON u.symbol=a.symbol
WHERE a.n = windowDays*2+1 AND a.prev_avg_vol>0 AND a.close_ago>0
  AND NOT (u.sector = (SELECT sector FROM universe WHERE symbol='429760') AND u.ksic IS NULL)
```
> `sector = (SELECT sector FROM universe WHERE symbol='429760')` 트릭은 세션 중 발견한
> 실무적 이유 때문: 이 저장소/터미널 환경에서 한글 리터럴을 쉘 heredoc으로 바로 SQL에 박으면
> (정규화 형식 차이로 추정) 문자열이 안 맞는 경우가 있었음. 구현 시엔 Java 코드에서 상수
> 문자열을 바로 바인드하면 이 문제가 재현되지 않을 가능성이 높으나(쉘 heredoc 특유의 문제일
> 수 있음), **구현 전에 반드시 실제 코드에서 `sector = '기타'`(UTF-8 리터럴)로 직접 바인딩해
> 실데이터로 재검증**하고, 안 맞으면 이 서브쿼리 방식(알려진 샘플 종목의 sector 값을 참조)을
> 그대로 채택할 것(QA 체크리스트에 추가, §10).

판정 후 신규 등록: `isAccumulating` 통과 + `existsActive` 아님 → `insert`(discoverySource,
valuationNote 포맷은 §6).

**만료 분기**(`removeFadedCandidates` 수정): discovery_source가 VOLUME_ACCUM이면 뉴스
판정을 완전히 스킵하고 `withinRetentionWindow`(기존 private 메서드, 그대로 재사용)만 적용.

## 9. 엣지케이스 & 에러 처리
- 42거래일(windowDays*2+1) 데이터가 없는 신규상장/거래정지 종목 → SQL의 `HAVING`/`WHERE a.n=...`
  조건으로 자동 제외(판단 보류, fail-closed — 기존 `rangeStatsBatch`와 동일 원칙).
- `prev_avg_vol`이 0(완전 거래정지 이력) → `WHERE a.prev_avg_vol>0`으로 제외(0으로 나누기 방지).
- 배치 실행 자체가 실패(DB 일시 오류) → 다음날 재시도로 충분(이 발굴은 긴급성 없음), 예외는
  로그만 남기고 스케줄러 전체를 죽이지 않음(기존 `@Scheduled` 에러 격리 패턴).
- 이미 `discovery_source='NEWS'`로 활성 후보인 종목이 같은 날 VOLUME_ACCUM 조건도 만족 →
  `existsActive` 체크로 중복 등록 안 됨(기존 source 유지, 덮어쓰지 않음).

## 10. 테스트 계획
- `VolumeAccumulationCheckerTest`: 경계값(ratio=threshold 정확히, pct=0/3 경계, turnover=0/null)
  단위테스트.
- `VolumeAccumulationDiscoveryServiceTest`: 배치 결과 모킹 → 통과 종목만 insert, 이미 활성인
  종목은 skip, insert 실패해도 나머지는 계속 진행.
- `DailyOhlcvMapperTest`(통합, H2 또는 테스트 DB): 42일 미달/거래정지/ETF(sector=기타+ksic null)
  케이스가 실제로 결과에서 빠지는지.
- **구현 직후 QA 체크리스트**: §8에서 언급한 `sector='기타'` 직접 리터럴 바인딩이 실제
  Java/MyBatis 파라미터 바인딩에서 정상 동작하는지 실데이터로 재확인(세션 중 발견한 쉘
  heredoc 특이 현상이 운영 코드에도 재현되는지 반드시 확인 — 안 되면 서브쿼리 방식 유지).
- `CandidateDiscoveryServiceTest`: VOLUME_ACCUM 후보가 뉴스 없이도 안 떨어지는지, 30일
  지나면 떨어지는지, NEWS 후보 기존 동작은 안 바뀌는지.

## 11. 리스크 & 대안 검토
- 대안1: 임계값(거래량비/구간/거래대금)을 `AutoTradeProperties`에 하드코딩 상수가 아니라
  설정값으로 뺌 — #808 다른 임계값들과 동일하게 "검증 단계, 데이터 쌓이면 재조정" 전제이므로
  채택(README 상단 주석 패턴 재사용).
- 대안2(기각): discovery_source 컬럼 대신 valuation_note 텍스트를 파싱해서 구분 — 깨지기
  쉬움(문자열 포맷 바뀌면 파싱도 깨짐), 컬럼 추가가 훨씬 안전하고 쿼리도 빨라서 기각.
  되돌리기 쉬운 결정(컬럼 추가는 가역적) — ADR 불필요.
- 리스크: 이번 세션 실측은 **특정 시점(2026-10-07) KR 데이터 1회 스냅샷**이라, 임계값이
  과최적화(overfit)됐을 수 있음. 최소 2~4주 드라이런 관찰 후 실매수 게이트에 영향 주는지
  재확인 권장(다만 이 발굴 경로 자체가 #808의 기존 매수 게이트를 그대로 통과해야 하므로,
  드라이런 없이도 실거래 리스크는 "발굴 풀 확대"로 제한적 — 그래도 데이터로 재검증은 필요).

## 12. 미해결 질문 (Open Questions)
- US 시장 확장 — `us_universe`(12,445) 전종목 정기백필 인프라 자체가 없음(#828과 동일 제약).
  별도 이슈로 분리할 것(인프라 과제: 전종목 일봉 백필 배치 신설, 이번 범위 아님).
- 매집 vs 분산 방향 판별(가격 위치/추세 맥락 결합) — v1은 발굴 풀 확대만, 방향 판별 고도화는
  드라이런 데이터 쌓인 후 재검토.
