package com.cloudhandson.tossstock.autotrade;

import com.cloudhandson.tossstock.market.DailyOhlcv;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** LiquidityChecker.isLiquid 검증. */
class LiquidityCheckerTest {

    private static DailyOhlcv row(int daysAgo, long close, long volume) {
        return new DailyOhlcv("TEST", LocalDate.now().minusDays(daysAgo), BigDecimal.valueOf(close),
                BigDecimal.valueOf(close), BigDecimal.valueOf(close), BigDecimal.valueOf(close), volume);
    }

    @Test
    void high_trading_value_passes() {
        List<DailyOhlcv> rows = List.of(row(1, 60000, 500000), row(2, 60000, 500000)); // 300억/일
        assertThat(LiquidityChecker.isLiquid(rows, BigDecimal.valueOf(500_000_000))).isTrue();
    }

    @Test
    void low_trading_value_fails() {
        List<DailyOhlcv> rows = List.of(row(1, 9000, 10000), row(2, 9000, 10000)); // 9천만/일
        assertThat(LiquidityChecker.isLiquid(rows, BigDecimal.valueOf(500_000_000))).isFalse();
    }

    @Test
    void empty_list_fails_closed() {
        assertThat(LiquidityChecker.isLiquid(List.of(), BigDecimal.valueOf(500_000_000))).isFalse();
    }

    @Test
    void null_list_fails_closed() {
        assertThat(LiquidityChecker.isLiquid(null, BigDecimal.valueOf(500_000_000))).isFalse();
    }
}
