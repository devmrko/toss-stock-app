#!/usr/bin/env python3
"""KRX ETF 전종목을 universe 시드에 반영한다 (#KODEX 검색 누락 보강).

소스: 네이버 금융 ETF 목록 API (KRX 데이터시스템 OTP 차단 대체).
출력:
  1) <out>/etf-seed.sql        : universe MERGE (멱등, 기존 종목 유지)
  2) src/main/resources/universe-krx.json 에 ETF 엔트리 추가(symbol 기준 dedup)

ETF 는 ksic/product 가 없으므로 market="ETF" 로 표기(검색결과에서 구분).
"""
import json, os, sys, urllib.request

NAVER = "https://finance.naver.com/api/sise/etfItemList.nhn"
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
JSON_PATH = os.path.join(ROOT, "src/main/resources/universe-krx.json")


def fetch_etfs():
    req = urllib.request.Request(NAVER, headers={
        "User-Agent": "Mozilla/5.0",
        "Referer": "https://finance.naver.com/sise/etf.nhn",
    })
    with urllib.request.urlopen(req, timeout=20) as r:
        # 네이버 금융은 EUC-KR(cp949) 로 응답한다 — utf-8 로 디코딩하면 한글이 깨진다.
        j = json.loads(r.read().decode("cp949"))
    out = []
    for it in j["result"]["etfItemList"]:
        code = str(it["itemcode"]).strip()
        name = str(it["itemname"]).strip()
        if len(code) == 6 and name:
            out.append({"symbol": code, "name": name, "market": "ETF"})
    return out


def sql_lit(s):
    """ASCII 전용 값(symbol/market)용 일반 문자열 리터럴."""
    return "'" + s.replace("'", "''") + "'"


def unistr_lit(s):
    """한글 등 비ASCII 포함 값용. UNISTR('\\XXXX') 로 ASCII 인코딩 →
    .sql 파일을 순수 ASCII 로 만들어 sqlcl 의 파일 charset 의존을 제거한다."""
    out = []
    for ch in s:
        o = ord(ch)
        if ch == "'":
            out.append("''")
        elif ch == "\\":
            out.append("\\005C")
        elif 0x20 <= o < 0x7F:
            out.append(ch)
        elif o <= 0xFFFF:
            out.append("\\%04X" % o)
        else:  # BMP 밖(서러게이트) — ETF명엔 없지만 안전하게 처리
            o -= 0x10000
            out.append("\\%04X\\%04X" % (0xD800 + (o >> 10), 0xDC00 + (o & 0x3FF)))
    return "UNISTR('" + "".join(out) + "')"


def main():
    out_dir = sys.argv[1] if len(sys.argv) > 1 else ROOT
    etfs = fetch_etfs()
    print(f"네이버 ETF 수신: {len(etfs)} 종목")

    # 1) etf-seed.sql — 배치 INSERT ALL (ADB 왕복 최소화).
    #    멱등: market='ETF' 선삭제 후 재적재. & 치환 방지 위해 SET DEFINE OFF.
    #    ETF 코드는 주식 코드와 겹치지 않고 market='ETF' 는 이 시드 전용이라 선삭제가 안전.
    BATCH = 100
    sql_path = os.path.join(out_dir, "etf-seed.sql")
    with open(sql_path, "w", encoding="utf-8") as f:
        f.write("SET DEFINE OFF\n")
        f.write("DELETE FROM universe WHERE market = 'ETF';\n")
        for i in range(0, len(etfs), BATCH):
            f.write("INSERT ALL\n")
            for e in etfs[i:i + BATCH]:
                f.write(
                    "  INTO universe (symbol, name, market) VALUES ("
                    f"{sql_lit(e['symbol'])}, {unistr_lit(e['name'])}, {sql_lit(e['market'])})\n"
                )
            f.write("SELECT 1 FROM dual;\n")
        f.write("COMMIT;\n")
    print(f"SQL 생성: {sql_path} ({len(etfs)} rows / {(len(etfs)+BATCH-1)//BATCH} INSERT ALL)")

    # 2) universe-krx.json 갱신: 기존 ETF 엔트리(이전 실행분 포함) 전부 제거 후 재추가.
    #    멱등 + 과거 잘못된 인코딩 엔트리 교정. 주식(market!=ETF) 엔트리는 보존.
    with open(JSON_PATH, encoding="utf-8") as f:
        uni = json.load(f)
    equities = [u for u in uni if u.get("market") != "ETF"]
    eq_symbols = {u["symbol"] for u in equities}
    etf_rows = [e for e in etfs if e["symbol"] not in eq_symbols]  # 주식코드와 충돌 방지
    merged = equities + etf_rows
    with open(JSON_PATH, "w", encoding="utf-8") as f:
        json.dump(merged, f, ensure_ascii=False)
    print(f"JSON 갱신: 주식 {len(equities)} + ETF {len(etf_rows)} = {len(merged)}")


if __name__ == "__main__":
    main()
