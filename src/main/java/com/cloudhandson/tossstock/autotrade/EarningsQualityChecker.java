package com.cloudhandson.tossstock.autotrade;

import java.math.BigDecimal;
import java.util.List;

/**
 * 원칙 §3-2 "실적의 질" — 최근 3년 매출·영업이익·순이익 우상향 + 핵심사업(영업이익) 흑자.
 * 데이터 부족(3년 미만) 시 안전 쪽(false, fail-closed).
 */
public final class EarningsQualityChecker {

    private EarningsQualityChecker() {
    }

    public static boolean hasThreeYearUptrend(AnnualFinancials f) {
        if (f == null || f.years().size() < 3) {
            return false;
        }
        List<AnnualFinancials.Year> last3 = f.years().subList(f.years().size() - 3, f.years().size());
        boolean revenueUp = isNonDecreasing(last3, AnnualFinancials.Year::revenue);
        boolean opUp = isNonDecreasing(last3, AnnualFinancials.Year::operatingProfit);
        boolean netUp = isNonDecreasing(last3, AnnualFinancials.Year::netIncome);
        BigDecimal latestOp = last3.get(2).operatingProfit();
        boolean coreProfitable = latestOp != null && latestOp.signum() > 0;
        return revenueUp && opUp && netUp && coreProfitable;
    }

    private static boolean isNonDecreasing(List<AnnualFinancials.Year> years,
                                            java.util.function.Function<AnnualFinancials.Year, BigDecimal> field) {
        for (int i = 1; i < years.size(); i++) {
            BigDecimal prev = field.apply(years.get(i - 1));
            BigDecimal cur = field.apply(years.get(i));
            if (prev == null || cur == null || cur.compareTo(prev) < 0) {
                return false;
            }
        }
        return true;
    }
}
