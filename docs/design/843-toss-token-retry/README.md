# 설계서: 토스 API 토큰 401 자동 재발급·재시도 (#843)

> **상태**: Approved
> **작성**: [AI] Architect · **최종수정**: 2026-10-07
> **추적성** — Redmine: #843 · 관련 ADR: 없음
> · 구현 파일: `TossAuthClient.java`(수정), `TossApiClient.java`(수정) ·
>   테스트: `TossApiClientTest`(신규 또는 기존 확장)

## 1. 목적 (Why)
실측(2026-10-07): `TossAuthClient.getAccessToken()`은 **로컬 만료시각**만 보고 캐시를
재사용한다. 토큰이 서버측에서 먼저 무효화되면(원인 무관 — 동시에 다른 클라이언트가 같은
client_credentials로 토큰을 새로 받아 기존 토큰이 revoke된 경우 등) 로컬 캐시는 "아직
안 지났다"고 보고 같은(이미 무효한) 토큰을 계속 써서 401이 반복된다.

실제 영향: 오늘 12:45부터 한동안 `AutoTradeScheduler.processHolding`(보유종목 손절
점검, 1분마다)이 401로 계속 실패 — 보유종목이 하드/트레일스탑을 넘어도 시스템이 못
보는 구간이 있었을 위험(실제 손절 누락 여부는 확인 안 됨, 재기동 전까지의 가격 변동
범위 내에서는 다행히 스탑 라인 돌파는 없었음). `holdings.html`의 currentPrice가 전부
null로 나온 것도 동일 원인. **재기동(토큰 캐시 초기화)으로만 복구**됐다 — 수동 개입
없이는 스스로 회복하지 못하는 구조.

## 2. 범위 (Scope)
- **포함**: `TossApiClient`의 인증이 필요한 모든 메서드(9개: `getPrices`, `getStocks`,
  `getAccounts`, `getDailyCandles`, `getDailyCandlePage`, `getBuyingPower`, `placeOrder`,
  `cancelOrder`, `getOrder`)에 "401 응답 시 토큰 강제 재발급 후 1회만 재시도" 공통 로직
  적용. `TossAuthClient`에 `forceRefresh()` 추가(캐시 무시하고 즉시 재발급).
- **제외 (out of scope)**: 401 이외의 에러(429, 5xx 등)에 대한 재시도/백오프 — 이번
  설계는 "토큰 무효화" 케이스만 다룬다(기존 429 처리 방식은 그대로, 호출측이 알아서
  스킵·다음 틱 재시도하는 기존 패턴 유지). 무한 재시도 방지를 위해 **최대 1회**로 제한.

## 3. 인수조건 (Acceptance Criteria)
- [ ] `TossApiClient`의 9개 메서드 모두, 첫 호출이 401이면 `TossAuthClient.forceRefresh()`
      호출 후 **정확히 1번만** 재시도한다. 재시도도 401이면 그대로 예외를 던진다(무한루프
      방지).
- [ ] 401이 아닌 다른 상태코드(429, 5xx 등)는 재시도 없이 기존처럼 즉시 예외를 던진다
      (기존 동작 회귀 없음).
- [ ] 첫 호출이 성공하면 `forceRefresh()`는 호출되지 않는다(정상 경로에 불필요한 토큰
      재발급 없음).
- [ ] 기존 테스트 전부 통과 + 신규 케이스(401→재발급→재시도 성공, 401→재발급→재시도도
      401→예외, 429는 재시도 없이 즉시 예외) 테스트 추가.

## 4. 컨텍스트 & 제약
- 의존성: 없음(신규 외부 I/O 없음 — 이미 있는 `/oauth2/token` 재호출을 트리거만 함).
- 제약: `TossAuthClient.getAccessToken()`/`forceRefresh()` 둘 다 `synchronized`라서
  동시에 여러 스레드가 401을 만나도 토큰 재발급은 1번만 일어남(기존 `getAccessToken()`의
  동기화 패턴을 그대로 재사용).
- 가정: Toss 쪽이 매번 새 client_credentials 토큰 발급 시 이전 토큰을 즉시 revoke하는
  정책이라면(오늘 실측상 그런 것으로 보임), 이 설계로도 "외부에서 누군가 새 토큰을
  받으면" 여전히 한 번은 401을 맞고 재시도로 복구하는 구조다 — 재시도 자체가 또 새
  토큰을 받으므로 또 다른 동시 요청자와 충돌할 수는 있으나, 1회 재시도로 실무적으로는
  충분하다고 판단(완전한 동시성 경합 해소는 범위 밖).

## 5. 아키텍처 개요
```
[TossApiClient.getPrices 등 9개 메서드]
        │
        ▼
  withAuthRetry(() -> { 기존 restClient 호출 전체 })
        │
        ├─ 1차 호출 성공 → 결과 반환
        │
        └─ TossApiException(status=401) 발생
               │
               ▼
          auth.forceRefresh()  ← 신규, 캐시 무시 즉시 재발급
               │
               ▼
          동일 호출 1회 재시도 → 성공/실패(그대로 던짐)
```
- I/O ↔ 순수 로직 경계: `withAuthRetry`는 I/O(call.get())를 감싸는 제어흐름 래퍼일 뿐,
  판정 로직(상태코드 401 여부)은 간단한 비교라 별도 순수 함수로 뽑을 필요 없음(§7).

## 6. 데이터 모델
변경 없음.

## 7. 함수 명세 (Function Specs)

| 함수 | 책임(1줄) | 시그니처 | 입력 | 출력 | 에러/실패 | 복잡? |
|------|-----------|----------|------|------|-----------|-------|
| `TossAuthClient.forceRefresh` | 캐시 무시하고 즉시 토큰 재발급 | `synchronized void forceRefresh()` | 없음 | void | 재발급 실패 시 기존 `requestToken()`과 동일하게 예외 전파 | 단순 |
| `TossApiClient.withAuthRetry` | 401 시 토큰 재발급 후 1회 재시도하는 공통 래퍼 | `private <T> T withAuthRetry(Supplier<T> call)` | 호출부 람다 | 호출 결과 | 401 아니면 즉시 재throw, 재시도도 401이면 재throw(무한루프 없음) | 단순(분기 1개, 외부 I/O는 내부 call이 담당) |
| 기존 9개 메서드(수정) | 호출 본문을 `withAuthRetry`로 감싸기만 함 | 시그니처 변경 없음 | 동일 | 동일 | 동일 | 단순(제어흐름 추가, 로직 불변) |

## 8. 흐름 / 알고리즘
`withAuthRetry`:
```java
private <T> T withAuthRetry(Supplier<T> call) {
    try {
        return call.get();
    } catch (TossApiException e) {
        if (e.getStatus() != 401) {
            throw e;
        }
        log.warn("토스 API 401 — 토큰 강제 재발급 후 1회 재시도");
        auth.forceRefresh();
        return call.get(); // 재시도도 실패하면 예외 그대로 전파(무한루프 없음)
    }
}
```
각 메서드는 기존 `restClient...body(...)` 블록 전체를 `withAuthRetry(() -> { ... })`로
감싸기만 하고, 그 안에서 `auth.getAccessToken()`을 그대로 호출(재시도 시 새로
재발급된 토큰을 자연히 읽게 됨 — `getAccessToken()`이 캐시를 읽는 구조라 추가 변경
불필요).

## 9. 엣지케이스 & 에러 처리
- 재시도도 401 → 그대로 `TossApiException` 전파(호출측의 기존 에러처리 — 로그만 남기고
  해당 틱 스킵 — 그대로 유지, 무한루프 방지가 최우선).
- `forceRefresh()` 자체가 실패(예: Toss 인증서버 장애) → `requestToken()`의 기존
  예외(`TossApiException: 토큰 발급 실패`)가 그대로 전파, 호출측은 이를 401이 아닌
  예외로 받아 재시도 없이 종료(이미 1회 재시도 한도 내).
- `getAccounts()`를 내부에서 호출하는 `firstAccountSeq()`도 `withAuthRetry`로 감싸인
  메서드(`getBuyingPower`/`placeOrder`/`cancelOrder`/`getOrder`) 안에서 호출되므로,
  그 안에서 401이 나도 바깥쪽 1회 재시도에 포함됨(이중 재시도 아님 — `getAccounts()`
  자체도 자신의 `withAuthRetry`를 가지지만, 바깥 호출이 성공적으로 끝나면 그걸로 종료).

## 10. 테스트 계획
- `TossApiClientTest`(신규, mockRestClient 또는 통합 스타일): 401→재발급→성공, 401→
  재발급→401(예외 전파, forceRefresh 1번만 호출됐는지 검증), 429(재시도 없음, forceRefresh
  호출 안 됨) — 최소 3케이스를 `getPrices` 하나로 대표 검증(나머지 8개는 동일 패턴이라
  중복 테스트 불필요, 와이어링만 확인).
- `TossAuthClientTest`(있으면 확장): `forceRefresh()`가 캐시를 무시하고 즉시
  `requestToken()`을 호출하는지.

## 11. 리스크 & 대안 검토
- 대안(기각): `RestClient`에 Spring Retry/Resilience4j 같은 선언적 재시도 라이브러리
  도입 — 더 일반적이지만 이번처럼 "401일 때만, 토큰 재발급을 끼워서, 정확히 1회"라는
  좁은 요구에는 과한 의존성 추가. 직접 래퍼 함수 10줄로 충분해 기각(되돌리기 쉬운
  결정 — 나중에 더 복잡한 재시도 정책이 필요해지면 라이브러리로 교체 가능).
- 리스크: 없음(기존 호출 패턴을 바꾸지 않고 감싸기만 함) — 가장 많이 쓰이는 기반
  클라이언트라 변경 자체는 신중해야 하지만, 변경 범위가 "래퍼로 감싸기"뿐이라 각
  메서드의 실제 로직(URI/파라미터/파싱)은 전혀 안 바뀜.

## 12. 미해결 질문 (Open Questions)
- 없음.
