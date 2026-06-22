-- Oracle DDL — 시장 전체 거래량 탑50 (#416). 멱등(ORA-00955 무시).
BEGIN
  EXECUTE IMMEDIATE q'[
    CREATE TABLE universe (
      symbol VARCHAR2(6)   PRIMARY KEY,
      name   VARCHAR2(100) NOT NULL,
      market VARCHAR2(10)  NOT NULL
    )
  ]';
EXCEPTION WHEN OTHERS THEN IF SQLCODE != -955 THEN RAISE; END IF;
END;
/
BEGIN
  EXECUTE IMMEDIATE q'[
    CREATE TABLE volume_rank (
      as_of        TIMESTAMP    NOT NULL,
      rnk          NUMBER       NOT NULL,
      symbol       VARCHAR2(6)  NOT NULL,
      name         VARCHAR2(100),
      market       VARCHAR2(10),
      volume       NUMBER,
      last_price   NUMBER,
      prev_close   NUMBER,
      change_rate  NUMBER
    )
  ]';
EXCEPTION WHEN OTHERS THEN IF SQLCODE != -955 THEN RAISE; END IF;
END;
/
