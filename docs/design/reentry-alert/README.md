# 설계서: 추적손절 재진입 알림 (Re-entry Alert)

> **상태**: Approved
> **작성**: [AI] Architect · **최종수정**: 2026-07-02
> **추적성** — Redmine: 없음(대화 중 즉시 요청, 이슈 미등록) · 관련 ADR: 없음
> · 구현 파일: `src/main/java/com/cloudhandson/tossstock/reentry/**` · 테스트: `src/test/java/com/cloudhandson/tossstock/reentry/ReentryStageCalculatorTest.java`

## 1. 목적 (Why)
추적손절 원칙(§4, `docs/reference/investment-principles.md`)에 따라 종목을 정리(매도)한 뒤, "돌파·재상승 확인 후 소량 재진입"을 판단할 수 있도록 가격 회복을 자동 감시하고 Discord로 알림한다. 물타기(무확인 재진입) 방지가 목적.

## 2. 범위 (Scope)
- **포함**: 기준가 대비 상승률을 1분 주기(장중)로 감시, 2단계(+5%/+10%) 임계값 도달 시 Discord 알림, 앱 자체 스프링 스케줄러 사용(외부 클라우드 스케줄러 불필요 — `PriceCache`로 로컬에서 바로 시세 접근 가능).
- **제외 (out of scope)**: 실제 매수/매도 주문 실행, 여러 종목 동시 관리 UI(현재는 DB에 행을 추가하면 자동으로 여러 종목 감시 가능하지만 전용 CRUD 화면은 없음), 알림 후 자동 재진입.

## 3. 인수조건 (Acceptance Criteria)
- [x] `active=1`인 감시 행마다 1분 주기(장중, 평일)로 현재가를 조회한다.
- [x] 상승률이 +10% 이상이면 2단계 알림을 보내고 해당 행을 `active=0`으로 비활성화한다(반복 알림 방지).
- [x] 상승률이 +5% 이상 +10% 미만이면 1단계 알림을 보내되, 같은 단계로는 재알림하지 않는다(멱등).
- [x] 웹훅 미설정 시 조용히 스킵(예외로 스케줄러를 죽이지 않음) — `BriefingService`와 동일 패턴.
- [x] 핵심 판단 로직(단계 결정)은 순수 함수로 분리해 DB/HTTP 없이 단위테스트 가능.

## 4. 컨텍스트 & 제약
- 의존성: 기존 `PriceCache`(시세, `TossApiClient` 경유), 기존 `DiscordClient`(웹훅 전송), Oracle(MyBatis) 상태 저장.
- 제약: Spring `@Scheduled` cron은 최소 단위 제약 없음(클라우드 스케줄러의 1시간 하한과 달리 1분도 가능) — 대신 실행마다 애플리케이션 프로세스가 떠 있어야 함(로컬 PC가 켜져 있을 때만 동작).
- 가정: 기준가·임계값은 감시 시작 시점에 DB 행으로 1회 입력(수동/SQL). 향후 필요하면 REST로 CRUD 추가 가능.

## 5. 아키텍처 개요
```
[Scheduler @Scheduled(매분, 장중)]
        │
        ▼
ReentryAlertService.checkAll()
        │  1) ReentryWatchMapper.findActive()  ← DB(Oracle/MyBatis)
        │  2) PriceCache.get([symbol])         ← 기존 시세 캐시 재사용
        │  3) ReentryStageCalculator.decide(...)  ← 순수 함수(테스트 대상)
        │  4) DiscordClient.send(webhook, msg) ← 기존 재사용
        │  5) ReentryWatchMapper.markStage1/markStage2  ← DB 갱신
        ▼
   Discord 채널
```
**I/O ↔ 순수 로직 경계**: 가격 조회(PriceCache)·알림 전송(DiscordClient)·상태 저장(Mapper)은 모두 `ReentryAlertService`(I/O 오케스트레이션)에 위치. "현재 상승률로 어느 단계인가"라는 판단만 `ReentryStageCalculator`(순수 함수, 부수효과 없음)로 분리.

## 6. 데이터 모델
테이블 `reentry_watch` (`db/reentry_watch.sql`):

| 컬럼 | 타입 | 제약 |
|------|------|------|
| id | NUMBER | PK, IDENTITY |
| symbol | VARCHAR2(20) | NOT NULL |
| reference_price | NUMBER | NOT NULL, 매도(정리) 시점 기준가 |
| stage1_pct | NUMBER | NOT NULL, 기본 5 |
| stage2_pct | NUMBER | NOT NULL, 기본 10 |
| stage1_alerted_at | TIMESTAMP | NULL 허용, 1단계 알림 발송 시각 |
| stage2_alerted_at | TIMESTAMP | NULL 허용, 2단계 알림 발송 시각 |
| active | NUMBER(1) | NOT NULL, 기본 1. 2단계 알림 후 0으로 자동 전환 |
| created_at | TIMESTAMP | NOT NULL, 기본 현재시각 |

## 7. 함수 명세 (Function Specs)

| 함수 | 책임(1줄) | 시그니처(잠정) | 입력 | 출력 | 에러/실패 | 복잡? |
|------|-----------|----------------|------|------|-----------|-------|
| `ReentryStageCalculator.decide` | 현재가·기준가·이미 보낸 단계로부터 이번에 보낼 단계를 결정 | `ReentryStage decide(BigDecimal current, BigDecimal reference, double stage1Pct, double stage2Pct, boolean stage1Done, boolean stage2Done)` | 현재가/기준가/임계값(%)/기발송 플래그 | `ReentryStage`(NONE/STAGE1/STAGE2) | 기준가 ≤ 0 → `IllegalArgumentException` | **복잡**(분기·비자명 우선순위) → `fn-decideStage.md` |
| `ReentryAlertService.checkAll` | 활성 감시 행 전체를 순회하며 시세 조회→판단→알림→상태갱신 | `void checkAll()` | 없음(스케줄러 트리거) | 없음 | 개별 행 실패는 로그만 남기고 다음 행 계속(전체 중단 금지) | **복잡**(외부 I/O·리스크 경로 아님이나 다중 I/O 오케스트레이션) → `fn-checkAll.md` |
| `ReentryWatchMapper.findActive/insert/markStage1/markStage2` | 단순 CRUD | MyBatis `@Mapper` | - | - | SQL 예외는 상위(`checkAll`)에서 catch | 단순 |
| `ReentryAlertProperties.enabled` | 웹훅 URL 설정 여부 판정(게터) | `boolean enabled()` | - | boolean | 없음 | 단순 |

## 8. 흐름 / 알고리즘
1. 스케줄러가 매분(장중, 평일) `checkAll()` 호출.
2. `active=1`인 감시 행을 전부 조회.
3. 행마다: 심볼 시세 조회 → `decide()`로 단계 판정 → `STAGE1`/`STAGE2`면 메시지 생성 후 Discord 전송 → 전송 성공 시 해당 단계 `*_alerted_at` 갱신(`STAGE2`는 추가로 `active=0`).
4. `NONE`이면 아무 것도 하지 않음.

## 9. 엣지케이스 & 에러 처리
- 시세 캐시에 해당 심볼 값이 아직 없음(콜드) → 이번 실행은 스킵, 다음 실행에 재시도(예외로 죽이지 않음).
- Discord 전송 실패(4xx/5xx/타임아웃) → `*_alerted_at`을 갱신하지 않아 다음 실행에 재시도(최소 1회 이상 도달 보장, 중복 전송 가능성은 감수).
- 웹훅 미설정(`DISCORD_WEBHOOK_URL` 없음) → `checkAll()` 진입 시 조기 반환, 로그만.
- 기준가 ≤ 0(데이터 오류) → `decide()`가 `IllegalArgumentException` 던짐 → `checkAll()`이 catch 후 해당 행만 스킵.

## 10. 테스트 계획
- 단위: `ReentryStageCalculatorTest` — 임계값 미만/1단계 경계/2단계 경계/이미 보낸 단계 재판정 안 함/기준가 0 이하 예외.
- 통합: 별도 IT 없음(기존 `OracleWatchlistIT` 패턴을 재사용할 수 있으나 이번 범위에서는 생략 — 수동 스모크로 대체, §12 참고).

## 11. 리스크 & 대안 검토
- **대안 검토**: 클라우드 크론(RemoteTrigger) 방식을 먼저 시도했으나 (a) 최소 간격 1시간 제약, (b) 로컬 앱의 실시간 `PriceCache`/DB에 접근 불가(퍼블릭 스크래핑 필요)라는 두 가지 근본적 한계로 기각. 앱 자체 스케줄러가 두 문제를 모두 해결.
- **리스크**: 로컬 PC/앱이 꺼져 있으면 감시도 멈춘다(클라우드 방식과 달리). 사용자가 이미 인지하고 선택한 트레이드오프.

## 12. 미해결 질문 (Open Questions)
- 감시 대상 추가/해제를 위한 REST API는 필요 시 후속 이슈로 분리(현재는 SQL 직접 삽입으로 충분).
