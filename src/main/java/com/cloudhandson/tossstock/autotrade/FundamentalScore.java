package com.cloudhandson.tossstock.autotrade;

/**
 * 원칙 §3 체크리스트 중 자동화 가능한 5개 항목(실적/재무/자본배분/시장성/상대강도)의 통과 개수.
 * 원문이 "대부분 YES"라고 했지 "전부 YES"는 아니므로, 5개 중 minPass개 이상이면 통과로 본다.
 * 설계: docs/design/808-auto-trade-engine/fn-fundamental-checklist.md
 */
public record FundamentalScore(boolean earningsQuality, boolean balanceSheet,
                                boolean capitalReturn, boolean liquidity, boolean relativeStrength) {

    public int passCount() {
        int n = 0;
        if (earningsQuality) n++;
        if (balanceSheet) n++;
        if (capitalReturn) n++;
        if (liquidity) n++;
        if (relativeStrength) n++;
        return n;
    }

    public boolean passes(int minPass) {
        return passCount() >= minPass;
    }

    /**
     * 항목별 통과 여부를 사람이 읽을 형태로(#884 탈락 사유 계측용).
     * 예) {@code 실적X재무O배당O유동O강도X} — BuyRationale 과 같은 표기를 쓴다.
     */
    public String describe() {
        return "실적" + ox(earningsQuality) + "재무" + ox(balanceSheet)
                + "배당" + ox(capitalReturn) + "유동" + ox(liquidity)
                + "강도" + ox(relativeStrength);
    }

    private static String ox(boolean b) {
        return b ? "O" : "X";
    }
}
