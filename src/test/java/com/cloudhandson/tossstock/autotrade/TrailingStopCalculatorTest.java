package com.cloudhandson.tossstock.autotrade;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** TrailingStopCalculator.decide 검증. 설계: docs/design/808-auto-trade-engine/fn-trailing-stop.md */
class TrailingStopCalculatorTest {

    @Test
    void above_stop_price_returns_none() {
        BigDecimal avgCost = BigDecimal.valueOf(100);
        BigDecimal peak = BigDecimal.valueOf(150);
        BigDecimal current = BigDecimal.valueOf(140); // stopPrice = max(90, 135) = 135
        assertThat(TrailingStopCalculator.decide(current, peak, avgCost, 10, 10)).isEqualTo(ExitReason.NONE);
    }

    @Test
    void below_trail_floor_triggers_trail_stop() {
        BigDecimal avgCost = BigDecimal.valueOf(100);
        BigDecimal peak = BigDecimal.valueOf(150);
        BigDecimal current = BigDecimal.valueOf(130); // stopPrice = max(90, 135) = 135, current <= 135
        assertThat(TrailingStopCalculator.decide(current, peak, avgCost, 10, 10)).isEqualTo(ExitReason.TRAIL_STOP);
    }

    @Test
    void no_new_high_falls_back_to_hard_stop() {
        BigDecimal avgCost = BigDecimal.valueOf(100);
        BigDecimal peak = BigDecimal.valueOf(100); // 신고점 없음 → trailFloor == hardFloor
        BigDecimal current = BigDecimal.valueOf(89); // hardFloor = 90
        assertThat(TrailingStopCalculator.decide(current, peak, avgCost, 10, 10)).isEqualTo(ExitReason.HARD_STOP);
    }

    @Test
    void exactly_at_stop_price_triggers_sell() {
        BigDecimal avgCost = BigDecimal.valueOf(100);
        BigDecimal peak = BigDecimal.valueOf(150);
        BigDecimal current = BigDecimal.valueOf(135); // 정확히 경계
        assertThat(TrailingStopCalculator.decide(current, peak, avgCost, 10, 10)).isNotEqualTo(ExitReason.NONE);
    }

    @Test
    void wider_trail_band_for_multibagger() {
        BigDecimal avgCost = BigDecimal.valueOf(100);
        BigDecimal peak = BigDecimal.valueOf(400); // 멀티배거
        BigDecimal current = BigDecimal.valueOf(330); // -17.5% from peak: -15%밴드론 매도, -20%밴드론 보유
        assertThat(TrailingStopCalculator.decide(current, peak, avgCost, 10, 15)).isEqualTo(ExitReason.TRAIL_STOP);
        assertThat(TrailingStopCalculator.decide(current, peak, avgCost, 10, 20)).isEqualTo(ExitReason.NONE);
    }

    @Test
    void peak_below_avg_cost_throws() {
        assertThatThrownBy(() -> TrailingStopCalculator.decide(
                BigDecimal.valueOf(90), BigDecimal.valueOf(95), BigDecimal.valueOf(100), 10, 10))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void non_positive_current_throws() {
        assertThatThrownBy(() -> TrailingStopCalculator.decide(
                BigDecimal.ZERO, BigDecimal.valueOf(100), BigDecimal.valueOf(100), 10, 10))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
