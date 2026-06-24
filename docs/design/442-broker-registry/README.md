# 설계서: 매매처 목록 등록·선택 + 기존 보유 매매처 수정 (#442)

> **상태**: Approved · **작성**: [AI] Architect · 2026-06-25
> **추적성** — Redmine: #442 · 기반: #441(매매처) · 구현: `db/broker.sql`, `broker/{BrokerMapper,BrokerSeeder}`, `web/BrokerController`, `holding/HoldingMapper(.xml)`, `web/HoldingController`, `config/SchemaInitializer`, `static/holdings.html`

## 1. 목적
① 국내 증권사(거래처) 목록을 **등록해서 선택**하게 한다(기본 시드 + 추가 가능).
② **기존 보유 종목의 매매처를 수정**할 수 있게 한다.

## 2. 인수조건
- [ ] `GET /api/brokers` 등록된 거래처 목록(이름, 정렬). `POST /api/brokers?name=` 신규 등록(멱등).
- [ ] 기동 시 국내 주요 증권사 시드(비어있을 때).
- [ ] 보유 추가 폼 매매처가 등록 목록에서 선택(+ 직접 입력 시 자동 등록).
- [ ] **거래(투자)별** 매매처 수정 — 거래내역(펼침)에서 거래마다 '🏦 지정' 클릭 → 변경(그 거래 1건). 종목 행은 매매처(중복결합) 읽기전용 표시.

## 3. 데이터/엔드포인트
- **BROKER_REF**(`name VARCHAR2(40) PK`): 거래처 마스터. 멱등 DDL + `BrokerSeeder`(빈 경우 국내 증권사 시드).
- `GET /api/brokers` → `List<String>`(이름 ASC). `POST /api/brokers?name=` → MERGE.
- `PUT /api/holdings/{id}/broker?broker=` → `HoldingMapper.updateBrokerById`(**거래 1건**). 빈 broker 면 NULL(해제). 매매처는 거래(투자)별 속성.

## 4. UI
- 로드시 `/api/brokers` → `<datalist id=brokerList>` 채움(폼 + 행편집 공용).
- 폼 제출 시 입력 매매처가 목록에 없으면 `POST /api/brokers` 자동 등록(거래 insert 가 broker 포함).
- 거래내역(펼침) 각 거래의 ‘🏦 지정’/매매처 클릭 → `prompt`(등록목록 안내) → 신규면 자동 등록 → `PUT /api/holdings/{id}/broker` → 재조회. 종목 행 매매처는 읽기전용.

## 5. 엣지케이스
- 빈 입력 → 매매처 해제(NULL).
- 중복 등록 → MERGE 무시.
- 시드: ORA-00955/기존 행 있으면 스킵.

## 6. 검증(라이브)
- /api/brokers 시드 N건, POST 추가, 기존 보유 PUT 수정 반영.

## 7. Open Questions
- (보류) 거래처 삭제/이름변경 관리 화면.
