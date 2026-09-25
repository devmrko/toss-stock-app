package com.cloudhandson.tossstock.autotrade;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** EarningsQualityChecker.hasThreeYearUptrend 검증. 설계: docs/design/808-auto-trade-engine/fn-fundamental-checklist.md */
class EarningsQualityCheckerTest {

    private static AnnualFinancials.Year year(String label, long rev, long op, long net) {
        return new AnnualFinancials.Year(label, BigDecimal.valueOf(rev), BigDecimal.valueOf(op),
                BigDecimal.valueOf(net), BigDecimal.TEN, BigDecimal.ONE);
    }

    @Test
    void three_year_uptrend_with_positive_operating_profit_passes() {
        AnnualFinancials f = new AnnualFinancials(List.of(
                year("2023", 100, 10, 8), year("2024", 120, 15, 12), year("2025", 150, 20, 16)));
        assertThat(EarningsQualityChecker.hasThreeYearUptrend(f)).isTrue();
    }

    @Test
    void declining_revenue_fails() {
        AnnualFinancials f = new AnnualFinancials(List.of(
                year("2023", 150, 10, 8), year("2024", 120, 15, 12), year("2025", 100, 20, 16)));
        assertThat(EarningsQualityChecker.hasThreeYearUptrend(f)).isFalse();
    }

    @Test
    void latest_year_operating_loss_fails_even_if_trend_up() {
        AnnualFinancials f = new AnnualFinancials(List.of(
                year("2023", 100, -20, -15), year("2024", 120, -10, -8), year("2025", 150, -5, -3)));
        assertThat(EarningsQualityChecker.hasThreeYearUptrend(f)).isFalse();
    }

    @Test
    void fewer_than_three_years_fails_closed() {
        AnnualFinancials f = new AnnualFinancials(List.of(year("2024", 120, 15, 12), year("2025", 150, 20, 16)));
        assertThat(EarningsQualityChecker.hasThreeYearUptrend(f)).isFalse();
    }

    @Test
    void null_financials_fails_closed() {
        assertThat(EarningsQualityChecker.hasThreeYearUptrend(null)).isFalse();
    }

    @Test
    void flat_revenue_still_passes_non_decreasing() {
        AnnualFinancials f = new AnnualFinancials(List.of(
                year("2023", 100, 10, 8), year("2024", 100, 15, 12), year("2025", 100, 20, 16)));
        assertThat(EarningsQualityChecker.hasThreeYearUptrend(f)).isTrue();
    }
}
