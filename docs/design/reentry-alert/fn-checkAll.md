# 함수 설계서: `ReentryAlertService.checkAll` (재진입 알림, 이슈 없음)

> **부모 설계서**: ./README.md · **상태**: Approved
> **작성**: [AI] Architect · **구현**: `reentry/ReentryAlertService.java:checkAll` · **테스트**: 수동 스모크(§12 README 참고), 핵심 판단은 `ReentryStageCalculatorTest`로 커버

## 1. 시그니처
```java
void checkAll()   // @Scheduled 진입점, 반환값 없음
```

## 2. 책임 (단일 책임, 1줄)
활성 감시 행 전체를 순회하며 시세 조회 → 단계 판정 → 필요 시 Discord 알림 → 상태 저장까지 오케스트레이션한다.

## 3. 입력
| 파라미터 | 타입 | 제약/검증 | 설명 |
|----------|------|-----------|------|
| (없음) | - | - | 스케줄러가 트리거, 내부에서 `ReentryWatchMapper.findActive()`로 대상 조회 |

## 4. 출력
- **반환**: 없음.
- **부수효과**: Discord 메시지 전송(외부 I/O), `reentry_watch` 행 갱신(`*_alerted_at`, `active`).

## 5. 동작 / 알고리즘
1. `props.enabled()`가 false면 즉시 반환(웹훅 미설정).
2. `mapper.findActive()`로 감시 대상 목록 조회. 비어있으면 반환.
3. 각 행에 대해:
   a. `priceCache.get(List.of(symbol))`로 현재가 조회 → 값 없으면(콜드) 이 행 스킵, 다음 행 계속.
   b. `ReentryStageCalculator.decide(...)` 호출.
   c. `STAGE1`/`STAGE2`면 메시지 포맷 후 `discord.send(webhookUrl, msg)`.
   d. 전송 성공(`true`)일 때만 해당 단계 컬럼 갱신(`STAGE2`는 `active=0`도 함께).
4. 개별 행 처리 중 예외 발생 시 로그만 남기고 다음 행으로 계속(한 종목 실패가 전체를 막지 않음).

## 6. 에러 & 실패 모드
| 조건 | 처리 | 반환/예외 |
|------|------|-----------|
| 웹훅 미설정 | 조기 반환, 로그 1줄 | 없음(정상 종료) |
| 시세 캐시 콜드/빈 값 | 해당 행 스킵 | 없음(다음 실행 재시도) |
| Discord 전송 실패 | 상태 갱신 안 함(재시도 유도) | 없음(예외 던지지 않음, `discord.send`가 boolean 반환) |
| DB 갱신 실패(SQL 예외) | catch 후 로그, 다음 행 계속 | 없음 |

## 7. 엣지케이스
- 동시에 여러 행이 같은 심볼을 감시 → `priceCache.get`이 한 번의 배치 조회로 처리되므로 문제 없음(다만 현재 시나리오는 행 1개).
- 스케줄러 두 실행이 겹침(직전 실행이 안 끝남) → Spring 기본 `@Scheduled`는 싱글스레드 순차 실행이라 겹치지 않음(이 프로젝트 설정 기준).

## 8. 복잡도 / 성능
- O(N) (N = 활성 감시 행 수, 현재 1). 매분 실행되지만 `PriceCache` 캐시(8초 TTL) 덕분에 실제 외부 API 호출 빈도는 낮음.

## 9. 의존성
- `ReentryWatchMapper`(DB), `PriceCache`(시세, 기존 컴포넌트 재사용), `DiscordClient`(웹훅, 기존 컴포넌트 재사용), `ReentryAlertProperties`(설정).

## 10. 테스트 케이스
- 순수 판단 로직은 `ReentryStageCalculatorTest`로 단위 검증(이 함수 자체는 I/O 오케스트레이션이라 단위테스트 대신 수동 스모크로 확인 — README §12 참고).
- [ ] 수동: 감시 행 1개 시드 후 애플리케이션 기동, 로그에 매분 실행 확인.
- [ ] 수동: 기준가를 현재가보다 낮게 임시 세팅해 STAGE1/STAGE2 알림이 실제 Discord로 오는지 확인.

## 11. 추적성
- 인수조건: README.md §3 전체.
- 관련 ADR: 없음.
