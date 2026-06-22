# ADR-0001: HTTP 클라이언트로 RestClient, 토큰은 메모리 캐시

> 상태: Accepted · 날짜: 2026-06-22 · 관련: #409

## 맥락
토스 Open API(REST, OAuth2 client_credentials)를 Spring Boot에서 호출해야 한다. HTTP 클라이언트 선택과 토큰 저장 전략이 필요하다.

## 결정
1. **HTTP 클라이언트 = `RestClient`** (Spring 6.1+ 동기 클라이언트).
2. **토큰 저장 = 단일 인스턴스 메모리 캐시** (`synchronized`).

## 근거
- 리액티브/논블로킹 요구 없음 → WebClient 과함. RestTemplate 은 유지보수 모드.
- `RestClient` 는 fluent + 동기 + 테스트 용이.
- 뼈대 단계는 단일 인스턴스 → 외부 토큰 스토어(Redis) 불필요. 토큰 수명 ~24h.

## 결과
- 멀티 인스턴스 수평확장 시 토큰 재발급이 인스턴스별 발생(레이트리밋 영향 가능) → 그때 공유 캐시로 재검토.
- WebSocket 실시간 시세가 필요해지면 별도 클라이언트 도입.
