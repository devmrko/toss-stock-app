-- #867 일회성 백필 — 기존 행의 UTC 시각을 KST 로 환산.
--
-- ★★ 재실행 금지 ★★  적용 완료: 2026-10-08 (앱 정지 상태, 단일 세션)
-- SchemaInitializer 의 스크립트 목록에 넣지 말 것 — 매 기동마다 9시간씩 밀린다.
--
-- 대상은 기본값이 SYSTIMESTAMP(UTC) 였던 두 컬럼뿐이다.
-- 이 DB 는 여러 프로젝트가 공유하므로 다른 테이블은 절대 건드리지 않는다.
--
-- stock_news.published_at 은 백필하지 않는다 — 올바른 보정값이 피드별·DST별로 달라
-- (CNBC EST/EDT, 다우존스 GMT) 저장값만으로 원래 오프셋을 복원할 수 없다. 일괄 가산은
-- 또 다른 오류를 만든다. 해당 행들은 TTL(최대 24h)을 지나 매매 판정에 쓰이지 않는다.

PROMPT === 백필 전 상태 ===
SELECT 'stock_news' AS tbl, COUNT(*) AS row_cnt,   -- 'rows' 는 Oracle 예약어라 별칭으로 쓸 수 없다
       TO_CHAR(MAX(fetched_at),'YYYY-MM-DD HH24:MI:SS') AS max_ts,
       TO_CHAR(CURRENT_TIMESTAMP,'YYYY-MM-DD HH24:MI:SS') AS now_kst
FROM stock_news
UNION ALL
SELECT 'holding', COUNT(*), TO_CHAR(MAX(created_at),'YYYY-MM-DD HH24:MI:SS'),
       TO_CHAR(CURRENT_TIMESTAMP,'YYYY-MM-DD HH24:MI:SS')
FROM holding;

UPDATE stock_news SET fetched_at = fetched_at + INTERVAL '9' HOUR;
UPDATE holding    SET created_at = created_at + INTERVAL '9' HOUR;
COMMIT;

PROMPT === 백필 후 검증 — max_ts 가 now_kst 를 넘지 않아야 한다(이중 가산 없음) ===
SELECT 'stock_news' AS tbl, COUNT(*) AS row_cnt,   -- 'rows' 는 Oracle 예약어라 별칭으로 쓸 수 없다
       TO_CHAR(MAX(fetched_at),'YYYY-MM-DD HH24:MI:SS') AS max_ts,
       TO_CHAR(CURRENT_TIMESTAMP,'YYYY-MM-DD HH24:MI:SS') AS now_kst,
       CASE WHEN MAX(fetched_at) <= CURRENT_TIMESTAMP THEN 'OK' ELSE 'FAIL' END AS verdict
FROM stock_news
UNION ALL
SELECT 'holding', COUNT(*), TO_CHAR(MAX(created_at),'YYYY-MM-DD HH24:MI:SS'),
       TO_CHAR(CURRENT_TIMESTAMP,'YYYY-MM-DD HH24:MI:SS'),
       CASE WHEN MAX(created_at) <= CURRENT_TIMESTAMP THEN 'OK' ELSE 'FAIL' END
FROM holding;
