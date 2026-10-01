package com.cloudhandson.tossstock.rangetrade;

import java.math.BigDecimal;
import java.math.MathContext;

/**
 * 현재가와 (보유 중이면 진입 시점에 고정된) 밴드 하단/상단으로 매수/익절/손절/유지를 판정(순수 함수).
 * 설계: docs/design/818-range-trade-swing/fn-range-trade-signal.md
 */
public final class RangeTradeSignal {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private RangeTradeSignal() {
    }

    public enum Signal {
        BUY,
        PROFIT_TAKE,
        RANGE_BREAKDOWN,
        NONE
    }

    /**
     * @param rangeLowAtEntry  미보유: 방금 산출한 밴드 하단 / 보유: <b>진입 시점에 고정 저장된</b> 하단
     *                         (트레일링 금지 — README §9: 하단을 갱신하면 손절을 영원히 피하는 함정)
     * @param holding          true 면 매도만 검토(BUY 판정 안 함)
     */
    public static Signal decide(BigDecimal current, BigDecimal rangeLowAtEntry, BigDecimal rangeHighAtEntry,
                                 boolean holding, RangeTradeProperties props) {
        if (current == null || current.signum() <= 0) {
            throw new IllegalArgumentException("current must be > 0: " + current);
        }
        if (rangeLowAtEntry == null || rangeHighAtEntry == null
                || rangeLowAtEntry.signum() <= 0
                || rangeLowAtEntry.compareTo(rangeHighAtEntry) >= 0) {
            throw new IllegalArgumentException("invalid band: low=" + rangeLowAtEntry + ", high=" + rangeHighAtEntry);
        }

        if (!holding) {
            return current.compareTo(entryCeiling(rangeLowAtEntry, props)) <= 0 ? Signal.BUY : Signal.NONE;
        }
        if (current.compareTo(profitFloor(rangeHighAtEntry, props)) >= 0) {
            return Signal.PROFIT_TAKE;
        }
        if (current.compareTo(breakdownFloor(rangeLowAtEntry, props)) <= 0) {
            return Signal.RANGE_BREAKDOWN;
        }
        return Signal.NONE;
    }

    /** 매수 상한선 — 밴드 하단 + entryZonePct%. 경계값 포함(<=). */
    public static BigDecimal entryCeiling(BigDecimal rangeLow, RangeTradeProperties props) {
        return scaled(rangeLow, props.entryZonePct());
    }

    /** 익절 하한선 — 밴드 상단 - exitZonePct%. 경계값 포함(>=). */
    public static BigDecimal profitFloor(BigDecimal rangeHigh, RangeTradeProperties props) {
        return scaled(rangeHigh, -props.exitZonePct());
    }

    /** 손절선 — 진입 시점 밴드 하단 - breakdownPct%. 경계값 포함(<=). */
    public static BigDecimal breakdownFloor(BigDecimal rangeLow, RangeTradeProperties props) {
        return scaled(rangeLow, -props.breakdownPct());
    }

    private static BigDecimal scaled(BigDecimal base, double pct) {
        BigDecimal factor = BigDecimal.ONE.add(BigDecimal.valueOf(pct).divide(HUNDRED, MathContext.DECIMAL64));
        return base.multiply(factor, MathContext.DECIMAL64);
    }
}
