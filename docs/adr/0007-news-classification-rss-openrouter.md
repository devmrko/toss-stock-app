# ADR-0007: 뉴스 S1–S5 분류 = 한국 RSS + OpenRouter(Claude)

> 상태: Accepted · 날짜: 2026-06-23 · 관련: #425 · 참고: upbit-bot x_signal

## 맥락
시장/섹터/종목 뉴스에 S1–S5(악재~호재)를 붙이고 싶음. 토스엔 뉴스/공시 API 없음(404). upbit-bot은 코인 뉴스 API+OpenRouter Claude로 동일 패턴 운영 중.

## 결정
1. **뉴스 소스 = 한국 금융 RSS**(한경·연합·전자신문). 검증 완료(items>0). 코인 뉴스 API는 주식 미커버라 미사용.
2. **분류기 = OpenRouter `anthropic/claude-haiku-4.5`**(upbit-bot 키 재사용, .env). STRICT JSON 출력.
3. **3단 타겟**: SYMBOL(회사명→`UNIVERSE.name` 매칭으로 6자리 코드) / SECTOR(기존 섹터 버킷) / MARKET. sentiment `코드:S레벨` CSV.
4. **카테고리 = 기존 `UNIVERSE.sector`** 재사용(별도 매핑 테이블 불필요).
5. **중복제거 v1 = ext_id(url/제목) exact**. 벡터 임베딩(OCI GenAI)은 후속.
6. **TTL**: S1/S5 24h, S2/S4 8h, S3 즉시(미노출).

## 근거
- RSS는 무료·안정·시장/섹터 커버리지 양호. LLM이 한 기사에서 시장/섹터/종목을 동시 태깅(실측 확인).
- 섹터를 이미 분류해둠 → 카테고리 재사용으로 구현 단순.

## 결과 / 함의
- 회사명 표기차로 일부 SYMBOL 매칭 실패 가능(섹터/시장은 유지). v2 별칭/부분매칭.
- LLM 비용은 신규 기사 수에 비례 → 주기·피드 수로 제어.
- 스크래핑(네이버 종목뉴스)은 약관/안정성 리스크로 v1 제외.
