package com.cloudhandson.tossstock.autotrade;

import com.cloudhandson.tossstock.market.DailyOhlcv;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.Comparator;
import java.util.List;

/**
 * 원칙 §3-7 "가격/추세" — 섹터·지수 대비 상대강세. 지수(코스피 프록시로 KODEX200) 대비
 * 같은 기간 수익률이 더 높으면 상대강세로 본다.
 */
public final class RelativeStrengthChecker {

    private RelativeStrengthChecker() {
    }

    public static boolean isRelativelyStrong(double stockReturnPct, double indexReturnPct) {
        return stockReturnPct > indexReturnPct;
    }

    /** rows(날짜순 무관, 내부 정렬)의 첫날 종가 대비 마지막날 종가 수익률(%). 데이터 2건 미만이면 null. */
    public static Double pctReturn(List<DailyOhlcv> rows) {
        if (rows == null || rows.size() < 2) {
            return null;
        }
        List<DailyOhlcv> sorted = rows.stream()
                .sorted(Comparator.comparing(DailyOhlcv::getTradeDate)).toList();
        BigDecimal first = sorted.get(0).getCloseP();
        BigDecimal last = sorted.get(sorted.size() - 1).getCloseP();
        if (first == null || last == null || first.signum() == 0) {
            return null;
        }
        return last.subtract(first).divide(first, MathContext.DECIMAL64).doubleValue() * 100;
    }
}
