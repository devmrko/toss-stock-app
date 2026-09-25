package com.cloudhandson.tossstock.autotrade;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/** ValuationChecker.isUndervalued 검증. 설계: docs/design/808-auto-trade-engine/fn-valuation-client.md */
class ValuationCheckerTest {

    @Test
    void both_under_threshold_passes() {
        Valuation v = new Valuation(BigDecimal.valueOf(15.5), BigDecimal.valueOf(1.44));
        assertThat(ValuationChecker.isUndervalued(v, 20.0, 2.0)).isTrue();
    }

    @Test
    void per_over_threshold_fails() {
        Valuation v = new Valuation(BigDecimal.valueOf(38.6), BigDecimal.valueOf(1.0));
        assertThat(ValuationChecker.isUndervalued(v, 20.0, 2.0)).isFalse();
    }

    @Test
    void pbr_over_threshold_fails() {
        Valuation v = new Valuation(BigDecimal.valueOf(12.8), BigDecimal.valueOf(3.33));
        assertThat(ValuationChecker.isUndervalued(v, 20.0, 2.0)).isFalse();
    }

    @Test
    void exactly_at_threshold_passes() {
        Valuation v = new Valuation(BigDecimal.valueOf(20.0), BigDecimal.valueOf(2.0));
        assertThat(ValuationChecker.isUndervalued(v, 20.0, 2.0)).isTrue();
    }

    @Test
    void null_valuation_fails_closed() {
        assertThat(ValuationChecker.isUndervalued(null, 20.0, 2.0)).isFalse();
    }

    @Test
    void null_per_or_pbr_fails_closed() {
        assertThat(ValuationChecker.isUndervalued(new Valuation(null, BigDecimal.ONE), 20.0, 2.0)).isFalse();
        assertThat(ValuationChecker.isUndervalued(new Valuation(BigDecimal.ONE, null), 20.0, 2.0)).isFalse();
    }

    @Test
    void negative_per_fails_closed() {
        Valuation v = new Valuation(BigDecimal.valueOf(-5.0), BigDecimal.valueOf(1.0));
        assertThat(ValuationChecker.isUndervalued(v, 20.0, 2.0)).isFalse();
    }
}
