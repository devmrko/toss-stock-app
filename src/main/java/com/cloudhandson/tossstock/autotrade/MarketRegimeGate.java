package com.cloudhandson.tossstock.autotrade;

import java.util.List;

/**
 * 시장상황(breadth + 최근 MARKET 뉴스) 기반 신규매수 허용 여부 판정(순수 함수).
 * 설계: docs/design/808-auto-trade-engine/fn-market-regime-gate.md
 */
public final class MarketRegimeGate {

    private MarketRegimeGate() {
    }

    /**
     * @param breadthPct        0~100. 시장 상승비율.
     * @param recentMarketS1S5  최근 lookback 기간 내 targets=MARKET 뉴스의 sentiment 레벨("S1".."S5") 목록.
     */
    public static boolean evaluate(int breadthPct, List<String> recentMarketS1S5, AutoTradeProperties.Gate props) {
        if (breadthPct < 0 || breadthPct > 100) {
            throw new IllegalArgumentException("breadthPct must be in [0,100]: " + breadthPct);
        }
        if (recentMarketS1S5 == null) {
            throw new IllegalArgumentException("recentMarketS1S5 must not be null (empty list allowed)");
        }

        boolean breadthOk = breadthPct >= props.minBreadthPct();

        long s1Count = recentMarketS1S5.stream().filter("S1"::equals).count();
        boolean newsOk = s1Count <= props.maxS1Count();

        return breadthOk && newsOk;
    }
}
