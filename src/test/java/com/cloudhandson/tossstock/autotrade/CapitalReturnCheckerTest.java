package com.cloudhandson.tossstock.autotrade;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** CapitalReturnChecker.paysDividend 검증. */
class CapitalReturnCheckerTest {

    private static AnnualFinancials withDividend(BigDecimal dps) {
        AnnualFinancials.Year y = new AnnualFinancials.Year("2025", BigDecimal.TEN, BigDecimal.ONE,
                BigDecimal.ONE, BigDecimal.TEN, dps);
        return new AnnualFinancials(List.of(y));
    }

    @Test
    void positive_dividend_passes() {
        assertThat(CapitalReturnChecker.paysDividend(withDividend(BigDecimal.valueOf(1668)))).isTrue();
    }

    @Test
    void zero_dividend_fails() {
        assertThat(CapitalReturnChecker.paysDividend(withDividend(BigDecimal.ZERO))).isFalse();
    }

    @Test
    void null_dividend_fails_closed() {
        assertThat(CapitalReturnChecker.paysDividend(withDividend(null))).isFalse();
    }

    @Test
    void null_financials_fails_closed() {
        assertThat(CapitalReturnChecker.paysDividend(null)).isFalse();
    }
}
