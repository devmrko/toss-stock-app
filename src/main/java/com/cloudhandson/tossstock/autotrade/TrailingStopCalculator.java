package com.cloudhandson.tossstock.autotrade;

import java.math.BigDecimal;
import java.math.MathContext;

/**
 * 하드손절/추적손절 매도 판정(순수 함수). 원칙 §4, 대화 중 시뮬레이션(simulate_trailing_stop.py)과 동일 공식.
 * 설계: docs/design/808-auto-trade-engine/fn-trailing-stop.md
 */
public final class TrailingStopCalculator {

    private TrailingStopCalculator() {
    }

    public static ExitReason decide(BigDecimal current, BigDecimal peak, BigDecimal avgCost,
                                     double hardStopPct, double trailPct) {
        if (current == null || current.signum() <= 0) {
            throw new IllegalArgumentException("current must be > 0: " + current);
        }
        if (avgCost == null || avgCost.signum() <= 0) {
            throw new IllegalArgumentException("avgCost must be > 0: " + avgCost);
        }
        if (peak == null || peak.compareTo(avgCost) < 0) {
            throw new IllegalArgumentException("peak must be >= avgCost: peak=" + peak + ", avgCost=" + avgCost);
        }

        BigDecimal hardFloor = avgCost.multiply(BigDecimal.valueOf(1 - hardStopPct / 100.0), MathContext.DECIMAL64);
        BigDecimal trailFloor = peak.multiply(BigDecimal.valueOf(1 - trailPct / 100.0), MathContext.DECIMAL64);
        BigDecimal stopPrice = hardFloor.max(trailFloor);

        if (current.compareTo(stopPrice) > 0) {
            return ExitReason.NONE;
        }
        boolean hitViaHard = current.compareTo(hardFloor) <= 0 && hardFloor.compareTo(trailFloor) >= 0;
        return hitViaHard ? ExitReason.HARD_STOP : ExitReason.TRAIL_STOP;
    }
}
