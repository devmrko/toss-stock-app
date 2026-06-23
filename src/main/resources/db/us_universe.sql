-- Oracle DDL — 미국 종목 검색 마스터 (#432). 멱등.
BEGIN
  EXECUTE IMMEDIATE q'[
    CREATE TABLE us_universe (
      symbol VARCHAR2(12)  PRIMARY KEY,
      name   VARCHAR2(150) NOT NULL,
      market VARCHAR2(12)
    )
  ]';
EXCEPTION WHEN OTHERS THEN IF SQLCODE != -955 THEN RAISE; END IF;
END;
/
