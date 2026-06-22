# ADR-0002: 영속 DB로 Oracle Autonomous DB(mTLS 월렛) 채택

> 상태: Accepted · 날짜: 2026-06-22 · 관련: #409 · 대체: H2(뼈대 초기값)

## 맥락
뼈대 초기엔 H2 인메모리를 썼으나, 운영 DB로 **Oracle Autonomous Database**(`Wallet_Q1VMFXPN6LHF72H5`, 서비스 `q1vmfxpn6lhf72h5_*`)를 사용하기로 결정. 실제 인스턴스는 **Oracle AI Database 26ai (23.26.2)** 로 확인됨.

## 결정
1. **메인 데이터소스 = Oracle ADB**, JDBC thin + SSO 월렛(`cwallet.sso`) 자동로그인.
   - 드라이버: `ojdbc11` + `oraclepki`(SSO 월렛 해독).
   - URL: `jdbc:oracle:thin:@${ORACLE_SERVICE}?TNS_ADMIN=${ORACLE_TNS_ADMIN}` (서비스 `_tp`).
   - 풀: HikariCP(기존 유지).
2. **시크릿/경로는 `.env`** (`ORACLE_TNS_ADMIN/SERVICE/USER/PASSWORD`) — 하드코딩·커밋 금지.
3. **자동 DDL 금지**: `spring.sql.init.mode=never`. 스키마는 `db/watchlist.sql`(멱등 PL/SQL) 로 수동/IT 적용.
4. **테스트 전략**:
   - 단위·컨텍스트 = H2(`MODE=Oracle`) hermetic, 프로파일 `test`(`application-test.yml`).
   - 실 Oracle 검증 = `OracleWatchlistIT`(프로파일 `oracle`), `ORACLE_PASSWORD` 있을 때만.

## 근거
- 실 OLTP 워크로드·운영 연속성 → ADB. `_tp` 서비스는 트랜잭션 처리용.
- SSO 월렛은 키스토어 비밀번호 불필요(자동로그인) → 설정 단순.
- 메인을 H2 로 가린 `application.yml` 전면 오버라이드는 `mybatis.mapper-locations` 유실 버그를 유발 → **프로파일 오버레이**로 전환.

## 결과 / 함의
- `ojdbc11`은 IDENTITY 컬럼의 generated key 반환 시 `keyColumn` 명시 필요(미지정 → ORA-17132). 매퍼에 반영.
- 월렛 SSL 인증서 만료: 2031-05-15 (그 전 재다운로드 필요).
- 멀티 인스턴스/마이그레이션 도구(Flyway/Liquibase)는 다음 단계에서 검토.
