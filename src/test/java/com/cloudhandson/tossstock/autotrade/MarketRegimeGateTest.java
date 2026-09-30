package com.cloudhandson.tossstock.autotrade;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** MarketRegimeGate.evaluate 검증. 설계: docs/design/808-auto-trade-engine/fn-market-regime-gate.md */
class MarketRegimeGateTest {

    private static final AutoTradeProperties.Gate GATE = new AutoTradeProperties.Gate(35);

    @Test
    void healthy_market_allows_buy() {
        assertThat(MarketRegimeGate.evaluate(50, GATE)).isTrue();
    }

    @Test
    void low_breadth_blocks_buy() {
        assertThat(MarketRegimeGate.evaluate(28, GATE)).isFalse();
    }

    @Test
    void breadth_exactly_at_threshold_allows() {
        assertThat(MarketRegimeGate.evaluate(35, GATE)).isTrue();
    }

    @Test
    void out_of_range_breadth_throws() {
        assertThatThrownBy(() -> MarketRegimeGate.evaluate(150, GATE))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MarketRegimeGate.evaluate(-5, GATE))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
