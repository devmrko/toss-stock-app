package com.cloudhandson.tossstock.autotrade;

import com.cloudhandson.tossstock.market.DailyOhlcv;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** RelativeStrengthChecker 검증. */
class RelativeStrengthCheckerTest {

    private static DailyOhlcv row(LocalDate date, long close) {
        return new DailyOhlcv("TEST", date, BigDecimal.valueOf(close), BigDecimal.valueOf(close),
                BigDecimal.valueOf(close), BigDecimal.valueOf(close), 1000L);
    }

    @Test
    void outperforming_index_passes() {
        assertThat(RelativeStrengthChecker.isRelativelyStrong(15.0, 5.0)).isTrue();
    }

    @Test
    void underperforming_index_fails() {
        assertThat(RelativeStrengthChecker.isRelativelyStrong(2.0, 5.0)).isFalse();
    }

    @Test
    void pct_return_computed_from_first_to_last_close() {
        LocalDate d0 = LocalDate.now().minusDays(10);
        List<DailyOhlcv> rows = List.of(row(d0, 100), row(d0.plusDays(5), 110), row(d0.plusDays(10), 120));
        assertThat(RelativeStrengthChecker.pctReturn(rows)).isEqualTo(20.0);
    }

    @Test
    void pct_return_with_insufficient_data_returns_null() {
        assertThat(RelativeStrengthChecker.pctReturn(List.of(row(LocalDate.now(), 100)))).isNull();
        assertThat(RelativeStrengthChecker.pctReturn(null)).isNull();
    }
}
