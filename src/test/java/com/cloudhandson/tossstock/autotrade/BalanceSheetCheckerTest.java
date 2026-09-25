package com.cloudhandson.tossstock.autotrade;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** BalanceSheetChecker.isHealthy 검증. */
class BalanceSheetCheckerTest {

    private static AnnualFinancials withDebtRatio(String debtRatio) {
        AnnualFinancials.Year y = new AnnualFinancials.Year("2025", BigDecimal.TEN, BigDecimal.ONE,
                BigDecimal.ONE, debtRatio == null ? null : new BigDecimal(debtRatio), BigDecimal.ONE);
        return new AnnualFinancials(List.of(y));
    }

    @Test
    void low_debt_ratio_passes() {
        assertThat(BalanceSheetChecker.isHealthy(withDebtRatio("29.94"), 200.0)).isTrue();
    }

    @Test
    void debt_ratio_over_threshold_fails() {
        assertThat(BalanceSheetChecker.isHealthy(withDebtRatio("250.0"), 200.0)).isFalse();
    }

    @Test
    void exactly_at_threshold_passes() {
        assertThat(BalanceSheetChecker.isHealthy(withDebtRatio("200.0"), 200.0)).isTrue();
    }

    @Test
    void missing_debt_ratio_fails_closed() {
        assertThat(BalanceSheetChecker.isHealthy(withDebtRatio(null), 200.0)).isFalse();
    }

    @Test
    void null_financials_fails_closed() {
        assertThat(BalanceSheetChecker.isHealthy(null, 200.0)).isFalse();
    }
}
