package com.cloudhandson.tossstock.rangetrade;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;

/**
 * #818 레인지 트랙 매수 결정근거 문자열 생성(순수 함수). 호출부(RangeTradeScheduler#tryBuy)에서 이미
 * 산출된 밴드값만 받아 포맷한다.
 * 예) {@code 밴드100000~130000 진입상한105000 매수가104000(밴드내13.33%)}
 * 설계: docs/design/832-decision-rationale-logging/README.md §6, §7
 */
public final class RangeBuyRationale {

    private static final String NA = "N/A";

    private RangeBuyRationale() {
    }

    /**
     * @param rangeLow     진입 시점 밴드 하단(null 허용)
     * @param rangeHigh    진입 시점 밴드 상단(null 허용)
     * @param entryCeiling {@link RangeTradeSignal#entryCeiling} 매수 상한선(null 허용)
     * @param currentPrice 매수 판정에 쓴 가격(null 허용)
     */
    public static String describe(BigDecimal rangeLow, BigDecimal rangeHigh,
                                   BigDecimal entryCeiling, BigDecimal currentPrice) {
        return "밴드" + plain(rangeLow) + "~" + plain(rangeHigh)
                + " 진입상한" + plain(entryCeiling)
                + " 매수가" + plain(currentPrice)
                + "(밴드내" + positionInBand(rangeLow, rangeHigh, currentPrice) + ")";
    }

    /** 밴드 내 위치(%) — 하단 0%, 상단 100%. 밴드 폭이 0·결측이면 N/A(0으로 나누기 방지). */
    private static String positionInBand(BigDecimal low, BigDecimal high, BigDecimal current) {
        if (low == null || high == null || current == null) {
            return NA;
        }
        BigDecimal width = high.subtract(low);
        if (width.signum() <= 0) {
            return NA;
        }
        double p = current.subtract(low).divide(width, MathContext.DECIMAL64).doubleValue() * 100;
        return plain(BigDecimal.valueOf(p)) + "%";
    }

    private static String plain(BigDecimal d) {
        if (d == null) {
            return NA;
        }
        return d.setScale(2, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
    }
}
