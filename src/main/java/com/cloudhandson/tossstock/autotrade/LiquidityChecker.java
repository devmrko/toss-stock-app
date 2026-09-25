package com.cloudhandson.tossstock.autotrade;

import com.cloudhandson.tossstock.market.DailyOhlcv;

import java.math.BigDecimal;

/**
 * 원칙 §3-6 "시장성" — 최근 거래일들의 평균 거래대금(종가×거래량)이 임계값 이상인지.
 * 너무 얇은 유동성(들어가고 나올 수 없는 종목)을 걸러낸다.
 */
public final class LiquidityChecker {

    private LiquidityChecker() {
    }

    public static boolean isLiquid(java.util.List<DailyOhlcv> recentDays, BigDecimal minAvgTradingValue) {
        if (recentDays == null || recentDays.isEmpty()) {
            return false;
        }
        BigDecimal total = BigDecimal.ZERO;
        int n = 0;
        for (DailyOhlcv d : recentDays) {
            if (d.getCloseP() == null || d.getVolume() == null) {
                continue;
            }
            total = total.add(d.getCloseP().multiply(BigDecimal.valueOf(d.getVolume())));
            n++;
        }
        if (n == 0) {
            return false;
        }
        BigDecimal avg = total.divide(BigDecimal.valueOf(n), java.math.MathContext.DECIMAL64);
        return avg.compareTo(minAvgTradingValue) >= 0;
    }
}
