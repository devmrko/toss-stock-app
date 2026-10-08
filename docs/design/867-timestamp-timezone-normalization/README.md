# 설계서: 타임스탬프 타임존 정규화 — 전부 KST로 (#867)

> **상태**: Approved · **작성**: [AI] Architect · 2026-10-08
> **추적성** — Redmine: #867 · 구현: `db/stock_news.sql`, `db/holding.sql`,
>   `mapper/StockNewsMapper.xml`, `news/RssClient` ·
>   테스트: `RssClientTest`(신규) · 일회성 마이그레이션: `db/migration/867-*.sql`
> **후속**: #872 `RISK_EVENT` 가 이 이슈를 선행조건으로 함

## 1. 목적 (Why)

### 1.1 원인 — `SYSTIMESTAMP` vs `CURRENT_TIMESTAMP`

| 식 | 의미 | 이 DB에서의 값 |
|----|------|----------------|
| `SYSTIMESTAMP` | 서버 시각 + **DB 타임존** | `11:14 +00:00` (**UTC**) |
| `CURRENT_TIMESTAMP` | 세션 시각 + **세션 타임존** | `20:14 ASIA/SEOUL` (**KST**) |

실측: `DBTIMEZONE=+00:00`, `SESSIONTIMEZONE=Asia/Seoul`, JVM=KST(앱 로그 `+09:00`).

이 프로젝트 테이블의 기본값이 두 종류로 섞여 있다.

| 컬럼 | 기본값 | 저장 기준 |
|------|--------|-----------|
| `auto_trade_order_log.created_at` | `CURRENT_TIMESTAMP` | **KST** |
| `auto_trade_position.created_at` | `CURRENT_TIMESTAMP` | **KST** |
| `auto_trade_candidate.created_at` | `CURRENT_TIMESTAMP` | **KST** |
| `range_trade_*.created_at` | `CURRENT_TIMESTAMP` | **KST** |
| `auto_trade_position.entry_at`/`exit_at` | (Java `LocalDateTime.now()`) | **KST** |
| `stock_news.expires_at` | (Java `LocalDateTime.now()`) | **KST** |
| **`stock_news.fetched_at`** | **`SYSTIMESTAMP`** | **UTC** ← 불일치 |
| **`holding.created_at`** | **`SYSTIMESTAMP`** | **UTC** ← 불일치 |

즉 **거의 전부 KST인데 두 컬럼만 UTC**다. 이 프로젝트 DDL 10개 파일 중
`SYSTIMESTAMP` 를 쓰는 것은 `stock_news.sql`, `holding.sql` 둘뿐이다.

> 이 DB는 여러 프로젝트가 공유한다(GTD/OCI_MGMT/MIND_LEARN/KNOWLEDGE 등).
> **다른 프로젝트 테이블은 건드리지 않는다** — 그쪽은 UTC 기준으로 일관될 수 있다.

### 1.2 이미 발생 중인 라이브 버그 2개

**① `findRecentEvents` 의 조회 구간이 24시간이 아니라 15시간이다.**

`CandidateDiscoveryService:77` 은 `LOOKBACK_HOURS = 24` 로 호출한다.

```java
newsMapper.findRecentEvents(LocalDateTime.now().minusHours(24))   // KST 기준 어제 20:14
```
```sql
WHERE kind = 'EVENT' AND fetched_at >= #{since}                   -- fetched_at 은 UTC
```

`fetched_at` 이 UTC 벽시계라 **9시간 과거로 기록**돼 있다. 결과적으로 경계가 밀려
**실효 조회 구간이 24−9=15시간**이다. 24시간 전 기사는 의도와 달리 제외된다.
`countSectorHotEvents` 도 같은 형태다.

**② `forSymbolBetween` 의 테마 재진입 판정(#840)이 어긋난다.**

```sql
AND COALESCE(published_at, fetched_at) BETWEEN #{from} AND #{to}
```
`from`/`to` 는 포지션의 `entry_at`/`exit_at`(KST)인데, `published_at` 이 없어
`fetched_at`(UTC)으로 떨어지는 행은 9시간 어긋나 구간 판정이 틀린다.

### 1.3 세 번째 불일치 — RSS `published_at`

`RssClient.parseDate`:

```java
return OffsetDateTime.parse(s, f).toLocalDateTime();   // 오프셋을 '변환 없이' 버린다
```

`toLocalDateTime()` 은 **시각을 변환하지 않고 오프셋만 떼어낸다**. 따라서
`+0900` 피드는 우연히 맞지만, **GMT/EST 피드는 그 숫자가 그대로 KST 로 저장**된다.

실측 — 설정된 피드 4개 중 2개가 해외 피드다.

| source | 행 수 | 발행 타임존 |
|--------|-------|-------------|
| `rss.etnews.com` | 30,185 | +0900 (정상) |
| `www.yna.co.kr` | 16,177 | +0900 (정상) |
| `www.hankyung.com` | 14,395 | +0900 (정상) |
| **`www.cnbc.com`** | **504** | EST/EDT → **틀림** |
| **`feeds.content.dowjones.io`** | **260** | GMT/EST → **틀림** |

하필 이 둘이 **미국 종목 기사**라, US 매매 판단에 쓰이는 시각이 틀어져 있다.

### 1.4 왜 지금 고치는가

#872 `RISK_EVENT` 는 "**진입 이후에** 나온 리스크 기사"를 판정해야 한다. 그러려면
`stock_news.fetched_at` 과 `auto_trade_position.entry_at` 을 비교해야 하는데, 지금은
9시간 어긋나 **진입 후 리스크 기사를 진입 전으로 오판**할 수 있다. 리스크 매도를
놓치는 방향이라 그냥 둘 수 없다.

## 2. 설계 (What) — 전부 KST(세션 로컬)로 통일

JVM·세션·거의 모든 컬럼이 이미 KST다. **UTC 쪽 2개를 KST로 맞추는 것이 최소 변경**이다
(반대로 전부 UTC로 가면 장 시간 판정·표시·`entry_at` 등 전부를 손대야 한다).

### 2.1 기본값 변경 (멱등 DDL)

```sql
ALTER TABLE stock_news MODIFY (fetched_at DEFAULT CURRENT_TIMESTAMP);
ALTER TABLE holding    MODIFY (created_at DEFAULT CURRENT_TIMESTAMP);
```

`MODIFY ... DEFAULT` 는 같은 값으로 반복 실행해도 예외가 없다 — `SchemaInitializer`
재실행에 안전하다.

### 2.2 비교식도 세션 로컬로 (`SYSTIMESTAMP` → `CURRENT_TIMESTAMP`)

`StockNewsMapper.xml` 의 만료 비교 3곳(`active`, `activeSentiments`, `expireOld`):

```sql
expires_at > SYSTIMESTAMP      -- 변경 전: KST TIMESTAMP vs UTC TSTZ
expires_at > CURRENT_TIMESTAMP -- 변경 후: KST TIMESTAMP vs KST TSTZ
```

**지금도 결과는 맞다** — Oracle 이 `TIMESTAMP` 피연산자를 **세션 타임존**으로 해석해
변환하기 때문이다(실측: `expires_at` 12:39 저장 / `SYSTIMESTAMP` 03:55 UTC → 판정
`만료`, 올바름). 그러나 **세션 타임존에 의존**하는 구조라, 드라이버·JVM TZ가 바뀌면
만료 판정이 조용히 깨진다. 양변을 세션 로컬로 맞춰 그 의존을 없앤다.

> 이 변경은 동작을 바꾸지 않는다(현재 세션이 KST이므로 동일 결과). **의도를 코드에
> 드러내고 숨은 전제를 제거**하는 변경이다.

### 2.3 RSS 발행시각을 KST로 변환

```java
return OffsetDateTime.parse(s, f).atZoneSameInstant(KST).toLocalDateTime();
```

`toLocalDateTime()`(오프셋 버림) → `atZoneSameInstant(Asia/Seoul)`(**같은 순간을 KST로
환산**). `KST` 는 `ZoneId.of("Asia/Seoul")` 상수.

### 2.4 기존 데이터 백필

| 대상 | 조치 | 비고 |
|------|------|------|
| `stock_news.fetched_at` (61,521행) | `+ INTERVAL '9' HOUR` | UTC→KST |
| `holding.created_at` (23행) | `+ INTERVAL '9' HOUR` | UTC→KST |
| `stock_news.published_at` (US 764행) | **백필하지 않음** | 아래 참조 |

**`published_at` 을 백필하지 않는 이유**: 올바른 보정값이 피드별·시점별로 다르다
(CNBC 는 EST −5 / EDT −4 로 DST 에 따라 바뀌고, 다우존스는 GMT 표기). 저장된 값만으로는
원래 오프셋을 복원할 수 없어 **일괄 가산은 또 다른 오류**를 만든다. 해당 행들은 TTL
(최대 24시간)을 이미 지나 매매 판정에 쓰이지 않는다 → **앞으로만 바르게 쌓는다**.

#### 경쟁 조건 제거 — 앱을 멈추고 한 번에

기본값을 바꾼 직후와 백필 사이에 새 행이 들어오면 **KST 행을 또 +9시간** 밀어버린다.
`identity` 는 단조증가하므로 `id <= B` 로 경계를 잡을 수 있지만, 그 경계를 캡처하는
순간과 `ALTER` 사이에 틈이 남는다. **앱을 멈추고 수행하면 틈이 0이 된다.**

지금이 그 작업에 가장 안전한 시점이다 — **장 마감 후(20시대), 보유 포지션 0건,
활성 후보 0건**. 순서:

```
1. pm2 stop toss-stock-app
2. ALTER 기본값 2건
3. UPDATE 백필 2건 (전체 행)
4. 검증 쿼리
5. pm2 start
```

마이그레이션 SQL 은 `db/migration/867-timestamp-to-kst.sql` 로 **저장소에 남기되
`SchemaInitializer` 목록에는 넣지 않는다** — 일회성이고 반복 실행하면 데이터가 망가진다.
파일 머리에 "적용 완료(2026-10-08), 재실행 금지"를 명시한다.

## 3. 함수 등재 (Function Registry)

| 대상 | 파일 | 변경 | I/O |
|------|------|------|-----|
| `stock_news` DDL | `db/stock_news.sql` | `fetched_at` 기본값 → `CURRENT_TIMESTAMP` | DDL |
| `holding` DDL | `db/holding.sql` | `created_at` 기본값 → `CURRENT_TIMESTAMP` | DDL |
| `active`, `activeSentiments`, `expireOld` | `mapper/StockNewsMapper.xml` | `SYSTIMESTAMP` → `CURRENT_TIMESTAMP` | SQL |
| `RssClient.parseDate` | `news/RssClient.java` | 오프셋 버림 → KST 환산 | 순수 |
| 일회성 백필 | `db/migration/867-timestamp-to-kst.sql` | 기존 행 +9시간 | DML(수동) |

신규 함수 없음 — 복잡 함수 설계서 불필요.

## 4. 인수조건 (Acceptance)

1. 새로 수집된 기사의 `fetched_at` 이 KST 로 기록된다(앱 로그 시각과 일치).
2. `findRecentEvents(now-24h)` 의 실효 조회 구간이 **24시간**이 된다.
3. 만료 판정 결과가 변경 전과 동일하다(동작 불변 — §2.2).
4. GMT/EST 피드의 `published_at` 이 KST 로 환산돼 저장된다.
5. `+0900` 피드의 `published_at` 은 값이 바뀌지 않는다(기존에 맞았으므로).
6. 백필 후 `stock_news.fetched_at` 최대값이 현재 KST 시각을 넘지 않는다(이중 가산 없음).
7. 다른 프로젝트 테이블(GTD/OCI_MGMT/…)은 변경되지 않는다.

## 5. 테스트 계획

`RssClientTest`(신규) — `parseDate` 는 `private static` 이므로 테스트를 위해
**package-private 으로 가시성만 완화**한다(로직 변경 없음):

- `Wed, 08 Oct 2026 20:14:00 +0900` → `2026-10-08T20:14` (불변, 인수조건 5)
- `Wed, 08 Oct 2026 11:14:00 GMT` → `2026-10-08T20:14` (환산, 인수조건 4)
- `2026-10-08T11:14:00Z` (ISO) → `2026-10-08T20:14`
- `2026-10-08T07:14:00-04:00` (EDT) → `2026-10-08T20:14`
- 파싱 불가 문자열 → `null`(기존 동작 유지)

DDL·백필은 단위테스트 대상이 아니므로 §4 의 검증 쿼리로 확인한다.

## 6. 리스크 / 되돌리기

| 리스크 | 완화 |
|--------|------|
| 백필 이중 가산 | 앱 정지 상태에서 단일 세션으로 수행, 전후 최대값 검증(인수조건 6) |
| 다른 프로젝트 테이블 오염 | `stock_news`·`holding` 만 명시적으로 지정. 와일드카드·루프 사용 금지 |
| 만료 판정이 바뀌어 뉴스가 한꺼번에 만료/부활 | §2.2 는 현재 세션(KST)에서 결과가 동일 — 배포 후 활성 건수를 전후 비교해 확인 |
| 앱 정지 중 매매 기회 상실 | 장 마감 후·보유 0건·후보 0건 시점에 수행 |

되돌리기: 기본값을 `SYSTIMESTAMP` 로 되돌리고 `− INTERVAL '9' HOUR` 로 역백필.
다만 그 사이 쌓인 KST 행과 섞이므로 **실질적으로 비가역** — 그래서 앱 정지 후
단일 세션으로 신중히 한 번만 수행한다.

## 7. 범위 밖

- 다른 프로젝트 소유 테이블의 타임존 정리.
- `published_at` 과거 데이터 보정(§2.4 — 복원 불가).
- `candle_cache.candle_ts`, `price_tick.ts` 등 외부 API 원본 시각 컬럼 — 이 프로젝트
  소유지만 기본값이 없고 외부 시세 API 가 주는 값을 그대로 저장한다. 별도 점검 필요.
