package com.cloudhandson.tossstock.autotrade;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** CircuitBreaker.check 검증. 설계: docs/design/808-auto-trade-engine/README.md §7 */
class CircuitBreakerTest {

    private static final BigDecimal BUDGET = BigDecimal.valueOf(5_000_000);

    @Test
    void above_threshold_not_tripped() {
        BigDecimal equity = BigDecimal.valueOf(4_300_000); // -14%
        assertThat(CircuitBreaker.check(equity, BUDGET, 15.0)).isFalse();
    }

    @Test
    void exactly_at_threshold_trips() {
        BigDecimal equity = BigDecimal.valueOf(4_250_000); // 정확히 -15%
        assertThat(CircuitBreaker.check(equity, BUDGET, 15.0)).isTrue();
    }

    @Test
    void beyond_threshold_trips() {
        BigDecimal equity = BigDecimal.valueOf(4_000_000); // -20%
        assertThat(CircuitBreaker.check(equity, BUDGET, 15.0)).isTrue();
    }

    @Test
    void profit_never_trips() {
        BigDecimal equity = BigDecimal.valueOf(6_000_000); // +20%
        assertThat(CircuitBreaker.check(equity, BUDGET, 15.0)).isFalse();
    }

    @Test
    void non_positive_budget_throws() {
        assertThatThrownBy(() -> CircuitBreaker.check(BigDecimal.valueOf(100), BigDecimal.ZERO, 15.0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
