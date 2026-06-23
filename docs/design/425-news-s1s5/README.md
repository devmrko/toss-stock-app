# 설계서: 뉴스 S1–S5 분류 (시장/섹터/종목) (#425)

> **상태**: Approved
> **작성**: [AI] Architect · **최종수정**: 2026-06-23
> **추적성** — Redmine: #425 · 관련 ADR: ADR-0007 · 참고: upbit-bot x_signal(#184/#199)
> · 구현: `news/*`, `web/NewsController.java`, `static/news.html`, `db/stock_news.sql`
> · 테스트: `NewsClassifierTest`, `NewsIngestServiceTest`

## 1. 목적 (Why)
한국 주식 뉴스를 수집·분류해 **시장 전체 / 섹터 / 종목** 단위로 **S1–S5**(악재~호재) 신호를 붙인다. upbit-bot의 x_signal 패턴을 주식에 이식.

## 2. 범위
- **포함**: 한국 금융 RSS 수집, OpenRouter(Claude) 분류, `stock_news` 저장, `/api/news` 서빙, `news.html`. 섹터=`UNIVERSE.sector` 재사용. TTL. EVENT/SPECULATION.
- **제외(후속)**: 벡터 임베딩 중복제거(v1은 URL/제목 exact dedup), 종목별 웹검색 심화수집, 알림(디스코드/텔레), 매매 게이팅.

## 3. S1–S5 체계 (upbit 동일)
| 레벨 | 의미 | 강도 |
|------|------|------|
| S1 | 강한 악재 | 강(2) 🔴 |
| S2 | 약한 악재 | 약(1) 🔴 |
| S3 | 중립/무관 | 0 ⚪ (저장만, 미노출) |
| S4 | 약한 호재 | 약(1) 🟢 |
| S5 | 강한 호재 | 강(2) 🟢 |

## 4. 인수조건
- [ ] RSS 수집 → 신규 기사만 `stock_news`(RECEIVED) 적재(ext_id unique dedup).
- [ ] LLM 분류 → targets(SYMBOL/SECTOR/MARKET)+S레벨, kind(EVENT/SPECULATION), 한국어 분석.
- [ ] SYMBOL 회사명 → `UNIVERSE.name` 매칭으로 6자리 코드 해석.
- [ ] `sentiment` 저장 형식 `005930:S5,반도체:S5,MARKET:S3`, `targets` `005930,반도체,MARKET`.
- [ ] TTL: S1/S5 24h, S2/S4 8h, S3 즉시. 만료시 EXPIRED.
- [ ] `GET /api/news?scope=market|sector|symbol&key=...` → 활성(미만료) 신호 최신순.
- [ ] `news.html`: 시장/섹터/종목 탭, S뱃지(🔴🟢), 제목·출처·시각·분석.
- [ ] 주기 수집(기본 10분) + 수동 `POST /api/news/ingest`.

## 5. 컨텍스트 & 제약
- **토스 뉴스 API 없음**(404) → 외부 RSS 필수.
- RSS: 한경(finance/economy)·연합경제·전자신문(검증됨, CDATA 제목).
- LLM: OpenRouter `anthropic/claude-haiku-4.5`(.env). 비용: 기사당 ~수백 토큰.
- 레이트리밋: RSS는 우리 서버↔매체(가벼움), LLM은 기사 수만큼(주기당 신규만).

## 6. 아키텍처
```
@Scheduled(10m) NewsIngestService.ingest()
   ├ RssClient.fetchAll(feeds) → List<NewsItem>(extId,title,url,source,publishedAt)
   ├ dedup: existsByExtId → 신규만 insert RECEIVED
   └ for 신규: NewsClassifier.classify(title) → {targets[],kind,analysis}
        ├ SYMBOL name → UniverseMapper.findCodeByName
        ├ sentiment/targets CSV 조립, expiresAt(strength)
        └ updateAnalyzed
[GET /api/news] StockNewsMapper.active(scope,key) → news.html
@Scheduled(1h) expireOld()
```
- **경계**: 분류 파싱·타겟 해석·TTL 계산은 순수 로직(테스트). RSS/LLM/DB는 I/O.

## 7. 데이터 모델
- **STOCK_NEWS**: `id PK`, `ext_id VARCHAR2(64) UNIQUE`(dedup), `title VARCHAR2(500)`, `url VARCHAR2(1000)`, `source VARCHAR2(60)`, `published_at TIMESTAMP`, `fetched_at TIMESTAMP`, `status VARCHAR2(12)`(RECEIVED/ANALYZED/EXPIRED), `targets VARCHAR2(400)`, `sentiment VARCHAR2(400)`, `rationale VARCHAR2(2000)`, `kind VARCHAR2(12)`, `model VARCHAR2(60)`, `expires_at TIMESTAMP`.
- **NewsItem**(수집 DTO): extId, title, url, source, publishedAt.
- **Classification**(LLM 결과): `List<Target>(type,name,level)`, kind, analysis. Target.type ∈ SYMBOL|SECTOR|MARKET.

## 8. 함수 명세
| 함수 | 책임 | 복잡? |
|------|------|-------|
| `RssClient.fetchAll` | 피드 파싱→NewsItem | **복잡** |
| `NewsClassifier.classify` | LLM 호출+JSON 파싱 | **복잡** |
| `NewsClassifier.resolveTargets` | 회사명→코드/섹터검증 | **복잡** |
| `NewsIngestService.ingest` | 수집→dedup→분류→저장 | **복잡** |
| `Strength.of/expiresAt` | S레벨 강도/TTL | 단순 |
| `StockNewsMapper.*` | CRUD/active/expire | 단순 |

> 복잡 함수 → `fn-news-pipeline.md`.

## 9. 엣지케이스
- 회사명 매칭 실패 → 그 SYMBOL 타겟 드롭(섹터/시장은 유지), 로그.
- 모든 타겟 S3 → 저장하되 active 조회 제외(노출 안 함).
- LLM JSON 파싱 실패 → RECEIVED 유지(다음 주기 재시도) 또는 스킵.
- RSS 일시 실패 → 해당 피드 스킵, 나머지 진행.
- 중복(같은 url/title) → ext_id unique 로 무시.
- 비-주식/무관 기사 → MARKET:S3 로 분류되어 미노출.

## 10. 테스트
- 단위: 분류 JSON 파싱(다타겟), TTL 계산, 회사명 해석(매칭/실패), sentiment CSV 조립.
- 통합(키 있을 때): RSS 1피드 fetch>0, LLM 1건 분류 200, ingest 라운드트립.

## 11. 리스크 & 대안
- LLM 오분류/환각 → kind=EVENT만 강조, 분석근거 노출로 사용자 판단. 모델 교체 가능(.env).
- 회사명 동음이의/표기차 → exact 매칭 우선, v2에서 별칭/부분매칭.
- 비용 → 신규 기사만 분류, S3 다수는 짧은 TTL. 주기/피드 수 조절.
- 대안(폐기): 토스 뉴스 API 없음 / 코인 뉴스 API 주식 미커버 / 스크래핑은 약관·안정성 리스크.

## 12. Open Questions
- 종목별 심화 수집(웹검색) 착수 시점(v2).
- 벡터 임베딩 의미중복제거 도입 여부(OCI GenAI 가용).
- 탑50/워치리스트 행에 S뱃지 통합 위치.
