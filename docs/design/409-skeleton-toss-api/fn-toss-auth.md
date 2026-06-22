# 함수 설계: 토스 OAuth2 토큰 발급/캐시 (#409)

> 부모 설계서: `README.md` · 대상: `TossAuthClient.getAccessToken` / `requestToken` / `TossToken.isExpired`

## 책임
토스 Open API 호출에 필요한 Bearer 토큰을 **만료 전 캐시**하며 제공한다. 매 API 호출마다 재발급하지 않는다.

## 시그니처
```java
String getAccessToken();          // 캐시 유효시 캐시, 아니면 재발급
TossToken requestToken();         // POST /oauth2/token (Basic auth)
boolean isExpired(long nowSec);   // TossToken: expiresAtEpochSec - 60 <= now
```

## 입력 / 출력 / 설정
- 설정(.env): `TOSS_API_BASE_URL`, `TOSS_CLIENT_KEY`, `TOSS_SECRET_KEY`.
- 요청: `POST {base}/oauth2/token`
  - 헤더: `Authorization: Basic base64(clientKey:secretKey)`, `Content-Type: application/x-www-form-urlencoded`
  - 바디: `grant_type=client_credentials`
- 응답: `{ "access_token", "token_type":"Bearer", "expires_in" }`

## 알고리즘
```
getAccessToken():
  synchronized(this):
    if cache == null or cache.isExpired(now()): cache = requestToken()
    return cache.accessToken

requestToken():
  resp = POST /oauth2/token (basic auth, grant_type=client_credentials)
  if resp != 2xx: throw TossApiException("token issue failed", status)
  return TossToken(access_token, token_type, now() + expires_in)
```

## 엣지케이스
- 401/403 → `TossApiException`, **재시도 금지**(자격증명 오류).
- 네트워크 타임아웃(5s) → 예외 전파.
- 만료 여유 60초(시계 오차/지연 대비).
- 동시 호출 → `synchronized` 로 1회만 재발급.

## 테스트
- 단위: `isExpired` 경계 (now == exp-60 → true, now == exp-61 → false). 캐시 재사용(두 번째 호출 시 requestToken 미호출 — Mockito).
- 통합: 실제 발급 200 + 토큰 길이 > 0 (`TossApiClientIT`).
