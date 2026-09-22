package com.cloudhandson.tossstock.autotrade;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** MarketRegimeGate.evaluate 검증. 설계: docs/design/808-auto-trade-engine/fn-market-regime-gate.md */
class MarketRegimeGateTest {

    private static final AutoTradeProperties.Gate GATE = new AutoTradeProperties.Gate(35, 1, 3);

    @Test
    void healthy_market_allows_buy() {
        assertThat(MarketRegimeGate.evaluate(50, List.of(), GATE)).isTrue();
    }

    @Test
    void low_breadth_blocks_buy() {
        assertThat(MarketRegimeGate.evaluate(28, List.of(), GATE)).isFalse();
    }

    @Test
    void too_many_s1_news_blocks_buy_even_with_good_breadth() {
        assertThat(MarketRegimeGate.evaluate(50, List.of("S1", "S1"), GATE)).isFalse();
    }

    @Test
    void breadth_exactly_at_threshold_allows() {
        assertThat(MarketRegimeGate.evaluate(35, List.of(), GATE)).isTrue();
    }

    @Test
    void s1_count_exactly_at_threshold_allows() {
        assertThat(MarketRegimeGate.evaluate(50, List.of("S1"), GATE)).isTrue();
    }

    @Test
    void empty_news_list_does_not_block() {
        assertThat(MarketRegimeGate.evaluate(50, List.of(), GATE)).isTrue();
    }

    @Test
    void out_of_range_breadth_throws() {
        assertThatThrownBy(() -> MarketRegimeGate.evaluate(150, List.of(), GATE))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MarketRegimeGate.evaluate(-5, List.of(), GATE))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
