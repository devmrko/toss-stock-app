# 설계서: 종목 섹터/테마 분류 태그 (#418)

> **상태**: Approved
> **작성**: [AI] Architect · **최종수정**: 2026-06-22
> **추적성** — Redmine: #418 · 관련: #416(탑50), ADR-0005
> · 구현: `market/SectorClassifier.java`, `Top50Service.enrich`, `static/top50.html`, `db/top50.sql`
> · 테스트: `SectorClassifierTest`

## 1. 목적 (Why)
탑50/리스트 종목에 **반도체·바이오·건설·화장품·엔터** 같은 테마 태그를 붙여 한눈에 섹터를 구분한다.

## 2. 범위
- **포함**: KRX `업종(KSIC)`+`주요제품`+`종목명` 키워드 기반 섹터 버킷 분류기, `UNIVERSE`에 ksic/product 저장, `VOLUME_RANK.sector` 저장, `top50.html` 태그 표시.
- **제외**: 다중 테마(1종목=1섹터로 단순화), AI 분류, 테마지수 정합성. 워치리스트 태그는 후속(동일 분류기 재사용).

## 3. 인수조건
- [ ] 삼성전자→반도체, SK하이닉스→반도체, 셀트리온/삼성바이오→바이오, 현대건설/GS건설→건설, 아모레→화장품, 하이브/JYP/SM→엔터, 크래프톤→게임으로 분류.
- [ ] 매칭 없으면 `기타`.
- [ ] 탑50 각 행에 섹터 태그 노출.
- [ ] 분류기는 순수 함수 + 단위 테스트.

## 4. 컨텍스트 & 제약
- KSIC 업종은 세분·부정확(삼성전자=통신장비, 아모레=화학) → **업종+제품+이름 종합** 필요.
- 분류는 정적(종목당 고정) → 스캔 시 1회 계산해 `VOLUME_RANK`에 저장.
- 키워드 우선순위 규칙(먼저 매칭되는 버킷 채택).

## 5. 아키텍처
```
UNIVERSE(symbol,name,market,ksic,product)  ← KRX 시드(5컬럼)
        │ (스캔 enrich 단계)
        ▼
SectorClassifier.classify(name,ksic,product) → bucket(String)
        ▼
VOLUME_RANK(..., sector)  → /api/top50 → top50.html 태그
```
- **경계**: 분류는 순수 로직(`SectorClassifier`), I/O 무관.

## 6. 데이터 모델
- `UNIVERSE` +`ksic VARCHAR2(60)`, +`product VARCHAR2(200)`.
- `VOLUME_RANK` +`sector VARCHAR2(20)`.
- 버킷(초기): 반도체, 2차전지, 디스플레이, 바이오, 의료기기, 화장품, 엔터, 미디어, 게임, IT/인터넷, 건설, 자동차, 조선, 방산, 철강, 화학, 금융, 식음료, 유통, 통신, 운송, 에너지, 기타.

## 7. 함수 명세
| 함수 | 책임 | 시그니처 | 복잡? |
|------|------|----------|-------|
| `SectorClassifier.classify` | 종합 텍스트→버킷 | `String classify(String name,String ksic,String product)` | **복잡** |

## 8. 알고리즘
```
text = name + " " + ksic + " " + product
for (bucket, keywords[]) in RULES (우선순위 순):
   if any keyword in text: return bucket
return "기타"
```
- RULES 순서가 핵심: 구체 테마(반도체/2차전지/화장품/엔터) 먼저, 광범위(IT/화학/유통) 나중.

## 9. 엣지케이스
- 복합기업(삼성전자: 통신+반도체) → 규칙 우선순위로 반도체 우선(투자 관점 대표 테마).
- 빈 업종/제품 → 이름만으로 시도, 실패 시 기타.
- 지주/스팩 → 대개 기타/금융.

## 10. 테스트
- `SectorClassifierTest`: 대표 종목 12+개 기대 버킷, 빈 입력→기타.

## 11. 리스크 & 대안
- 키워드 규칙은 근사치(오분류 가능) → 버킷/키워드는 조정 가능하게 상수화. 대안(폐기): 외부 테마분류 API 없음/유료.
- 단일 테마 단순화 → 다중 테마는 후속.

## 12. Open Questions
- 워치리스트에도 태그 적용 시점.
- 2차전지 vs 화학 경계(양극재 업체) 세부 조정.
