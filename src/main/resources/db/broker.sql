-- Oracle DDL — 매매처(증권사) 마스터 (#442). 멱등.
BEGIN
  EXECUTE IMMEDIATE q'[
    CREATE TABLE broker_ref (
      name VARCHAR2(40) PRIMARY KEY
    )
  ]';
EXCEPTION WHEN OTHERS THEN IF SQLCODE != -955 THEN RAISE; END IF;
END;
/
