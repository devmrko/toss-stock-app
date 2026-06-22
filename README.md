# toss-stock-app

토스증권 **Open API** 연동 주식 앱. Spring Boot 3 + HikariCP + MyBatis.
현재 단계: **뼈대(skeleton) + 읽기 API 연동 검증** (Redmine #409).

## 스택
- Java 21, Spring Boot 3.3.5
- HTTP: `RestClient` (ADR-0001)
- DB: H2(인메모리) + MyBatis + HikariCP
- 설정/비밀: `.env` (spring-dotenv) — **하드코딩·커밋 금지**

## 토스 Open API (검증 완료)
| 용도 | 호출 |
|------|------|
| 토큰 | `POST /oauth2/token` (Basic `base64(clientKey:secretKey)`, `grant_type=client_credentials`) |
| 시세 | `GET /api/v1/prices?symbols=005930` |
| 종목 | `GET /api/v1/stocks?symbols=005930` |
| 계좌 | `GET /api/v1/accounts` |

> 주문(`POST /api/v1/orders`)은 **이번 범위 아님**(리스크). 읽기 전용.

## 설정 (.env)
`.env.example` 복사 후 채우기:
```
TOSS_API_BASE_URL=https://openapi.tossinvest.com
TOSS_CLIENT_KEY=...
TOSS_SECRET_KEY=...
```

## 실행
```bash
set -a; . ./.env; set +a            # .env → 환경변수
mvn spring-boot:run                  # http://localhost:8080
```

### 앱 엔드포인트
- `GET /api/quote?symbols=005930` — 시세
- `GET /api/stock?symbols=005930` — 종목정보
- `GET /api/account` — 계좌목록
- `GET /api/watchlist` — MyBatis/HikariCP 스모크

## 테스트
```bash
mvn test                             # 단위 + 컨텍스트/MyBatis 스모크
set -a; . ./.env; set +a; mvn verify # + 실제 토스 API 통합 테스트(*IT)
```
키가 없으면 `*IT` 는 자동 skip(`@EnabledIfEnvironmentVariable`).

## 문서 (cloud-handson 컨벤션)
- 설계서: `docs/design/409-skeleton-toss-api/`
- ADR: `docs/adr/0001-restclient-and-memory-token-cache.md`
- 표준/파이프라인: `CLAUDE.md`, `docs/README.md`
- 추적성: Redmine #409 ↔ 설계서 ↔ git 커밋
