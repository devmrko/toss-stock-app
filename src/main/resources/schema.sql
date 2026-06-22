-- HikariCP + MyBatis 스모크용 테이블 (H2 인메모리)
CREATE TABLE IF NOT EXISTS watchlist (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    symbol     VARCHAR(20)  NOT NULL,
    memo       VARCHAR(200),
    created_at TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- 시드: 삼성전자
INSERT INTO watchlist (symbol, memo) VALUES ('005930', '삼성전자 - seed');
