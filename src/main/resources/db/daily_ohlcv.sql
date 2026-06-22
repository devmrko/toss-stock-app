-- Oracle DDL — 전체 시장 일봉 OHLCV (#419). 멱등.
BEGIN
  EXECUTE IMMEDIATE q'[
    CREATE TABLE daily_ohlcv (
      symbol     VARCHAR2(6)  NOT NULL,
      trade_date DATE         NOT NULL,
      open_p     NUMBER,
      high_p     NUMBER,
      low_p      NUMBER,
      close_p    NUMBER,
      volume     NUMBER,
      CONSTRAINT pk_daily_ohlcv PRIMARY KEY (symbol, trade_date)
    )
  ]';
EXCEPTION WHEN OTHERS THEN IF SQLCODE != -955 THEN RAISE; END IF;
END;
/
-- UNIVERSE 에 섹터 컬럼(사전 분류 저장)
BEGIN
  EXECUTE IMMEDIATE 'ALTER TABLE universe ADD (sector VARCHAR2(20))';
EXCEPTION WHEN OTHERS THEN IF SQLCODE != -1430 THEN RAISE; END IF;  -- 이미 존재
END;
/
