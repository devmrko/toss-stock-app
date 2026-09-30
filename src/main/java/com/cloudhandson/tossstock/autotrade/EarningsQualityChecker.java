package com.cloudhandson.tossstock.autotrade;

import java.math.BigDecimal;
import java.util.List;

/**
 * 원칙 §3-2 "실적의 질" — 최근 3년 매출·영업이익·순이익 우상향 + 핵심사업 흑자.
 * 데이터 부족(3년 미만) 시 안전 쪽(false, fail-closed).
 * 2026-09-30: 야후(US) 무료 API는 영업이익이 거의 항상 null(실측, LOCO 등) — 3년 전부
 * 영업이익 데이터가 없으면 매출+순이익만으로(순이익 흑자를 핵심사업 흑자의 대리지표로)
 * 대체 판정. 영업이익 데이터가 있는데 감소/적자면(KR 대부분) 원래대로 엄격 적용.
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
        boolean netUp = isNonDecreasing(last3, AnnualFinancials.Year::netIncome);
        BigDecimal latestNet = last3.get(2).netIncome();
        boolean netProfitable = latestNet != null && latestNet.signum() > 0;

        boolean operatingProfitDataMissing = last3.stream().allMatch(y -> y.operatingProfit() == null);
        if (operatingProfitDataMissing) {
            // 데이터 소스가 영업이익을 안 주는 경우(US 무료 API 한계) — 매출+순이익만으로 판정.
            return revenueUp && netUp && netProfitable;
        }

        boolean opUp = isNonDecreasing(last3, AnnualFinancials.Year::operatingProfit);
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
