package com.cloudhandson.tossstock.market;

import com.cloudhandson.tossstock.market.MarketMetrics.Metrics;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MarketMetricsTest {

    private static DailyOhlcv d(String date, long close, long high, long low, long vol) {
        return new DailyOhlcv("X", LocalDate.parse(date),
                BigDecimal.valueOf(close), BigDecimal.valueOf(high),
                BigDecimal.valueOf(low), BigDecimal.valueOf(close), vol);
    }

    @Test
    void computes_flow_trend_dip_swing_value() {
        List<DailyOhlcv> daily = List.of(
                d("2026-06-01", 100, 105, 95, 10),
                d("2026-06-02", 110, 115, 100, 20),
                d("2026-06-03", 90, 112, 88, 30),
                d("2026-06-04", 120, 125, 115, 40),
                d("2026-06-05", 130, 135, 118, 50));

        Metrics m = MarketMetrics.of(daily, null);

        assertThat(m.flow()).containsExactly(100L, 110L, 90L, 120L, 130L);
        assertThat(m.lastClose()).isEqualTo(130L);
        assertThat(m.trendPct()).isEqualTo(18.18);   // (130-110)/110*100
        assertThat(m.dipPct()).isEqualTo(3.70);       // (135-130)/135*100
        assertThat(m.swingPos()).isEqualTo(89);       // (130-88)/(135-88)*100
        assertThat(m.swingLow()).isEqualTo(88L);
        assertThat(m.swingHigh()).isEqualTo(135L);
        assertThat(m.tradingValue()).isEqualTo(6500L); // 50 * 130
    }

    @Test
    void uses_live_price_when_provided() {
        List<DailyOhlcv> daily = List.of(
                d("2026-06-01", 100, 100, 100, 10),
                d("2026-06-02", 100, 100, 100, 10));
        Metrics m = MarketMetrics.of(daily, BigDecimal.valueOf(120));
        assertThat(m.lastClose()).isEqualTo(120L);
    }

    @Test
    void flat_band_gives_mid_swing() {
        List<DailyOhlcv> daily = List.of(
                d("2026-06-01", 100, 100, 100, 10),
                d("2026-06-02", 100, 100, 100, 10),
                d("2026-06-03", 100, 100, 100, 10));
        Metrics m = MarketMetrics.of(daily, null);
        assertThat(m.swingPos()).isEqualTo(50);
        assertThat(m.dipPct()).isEqualTo(0.0);
        assertThat(m.trendPct()).isEqualTo(0.0);
    }

    @Test
    void insufficient_data_is_empty() {
        Metrics m = MarketMetrics.of(List.of(d("2026-06-01", 100, 100, 100, 10)), null);
        assertThat(m.flow()).isEmpty();
        assertThat(m.trendPct()).isNull();
        assertThat(m.swingPos()).isNull();
    }
}
