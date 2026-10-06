package com.cloudhandson.tossstock.rangetrade;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;

/**
 * #818 레인지 트랙 매도 결정근거 문자열 생성(순수 함수). 호출부(RangeTradeScheduler#processHolding)에서
 * 이미 산출된 값만 받아 포맷한다.
 * 예) {@code 익절(익절선117000) 현재120000 진입105000 수익+14.29% 밴드100000~130000 손절선95000}
 * 설계: docs/design/832-decision-rationale-logging/README.md §6, §7
 */
public final class RangeSellRationale {

    private static final String NA = "N/A";

    private RangeSellRationale() {
    }

    /**
     * @param reason         발동한 매도 사유(null 허용)
     * @param entryPrice     진입가(null 허용)
     * @param rangeLow       진입 시점 밴드 하단(null 허용)
     * @param rangeHigh      진입 시점 밴드 상단(null 허용)
     * @param profitFloor    {@link RangeTradeSignal#profitFloor} 익절 하한선(null 허용)
     * @param breakdownFloor {@link RangeTradeSignal#breakdownFloor} 손절선(null 허용)
     * @param currentPrice   판정에 쓰인 현재가(null 허용)
     */
    public static String describe(RangeExitReason reason, BigDecimal entryPrice, BigDecimal rangeLow,
                                   BigDecimal rangeHigh, BigDecimal profitFloor, BigDecimal breakdownFloor,
                                   BigDecimal currentPrice) {
        return label(reason) + "(" + trigger(reason, profitFloor, breakdownFloor) + ")"
                + " 현재" + plain(currentPrice)
                + " 진입" + plain(entryPrice)
                + " 수익" + changePct(entryPrice, currentPrice)
                + " 밴드" + plain(rangeLow) + "~" + plain(rangeHigh)
                + " 익절선" + plain(profitFloor) + "/손절선" + plain(breakdownFloor);
    }

    private static String label(RangeExitReason reason) {
        if (reason == null) {
            return "매도";
        }
        return switch (reason) {
            case PROFIT_TAKE -> "익절";
            case RANGE_BREAKDOWN -> "밴드이탈손절";
            case BAD_NEWS -> "악재매도";
            case MANUAL -> "수동매도";
        };
    }

    /** 발동한 사유의 임계선만 괄호 앞머리에 강조 — 사후에 "무엇이 바인딩이었나"를 바로 읽히게. */
    private static String trigger(RangeExitReason reason, BigDecimal profitFloor, BigDecimal breakdownFloor) {
        if (reason == RangeExitReason.PROFIT_TAKE) {
            return "익절선" + plain(profitFloor);
        }
        if (reason == RangeExitReason.RANGE_BREAKDOWN) {
            return "손절선" + plain(breakdownFloor);
        }
        return "가격무관";
    }

    /** from 대비 to 의 변동률. from 이 0·null 이면 N/A(0으로 나누기 방지). */
    private static String changePct(BigDecimal from, BigDecimal to) {
        if (from == null || to == null || from.signum() <= 0) {
            return NA;
        }
        double p = to.subtract(from).divide(from, MathContext.DECIMAL64).doubleValue() * 100;
        return (p >= 0 ? "+" : "") + plain(BigDecimal.valueOf(p)) + "%";
    }

    private static String plain(BigDecimal d) {
        if (d == null) {
            return NA;
        }
        return d.setScale(2, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
    }
}
