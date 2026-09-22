package com.cloudhandson.tossstock.autotrade;

import java.math.BigDecimal;
import java.math.MathContext;

/**
 * 전체 평가손익 기준 서킷브레이커 판정(순수 함수).
 * 설계: docs/design/808-auto-trade-engine/README.md §7
 */
public final class CircuitBreaker {

    private CircuitBreaker() {
    }

    /** currentEquity 가 initialBudget 대비 thresholdPct(%) 이상 하락했으면 true(트립). */
    public static boolean check(BigDecimal currentEquity, BigDecimal initialBudget, double thresholdPct) {
        if (initialBudget == null || initialBudget.signum() <= 0) {
            throw new IllegalArgumentException("initialBudget must be > 0: " + initialBudget);
        }
        if (currentEquity == null) {
            throw new IllegalArgumentException("currentEquity must not be null");
        }
        BigDecimal pnlPct = currentEquity.subtract(initialBudget)
                .divide(initialBudget, MathContext.DECIMAL64)
                .multiply(BigDecimal.valueOf(100));
        return pnlPct.compareTo(BigDecimal.valueOf(-thresholdPct)) <= 0;
    }
}
