package com.cloudhandson.tossstock.autotrade;

import com.cloudhandson.tossstock.market.DailyOhlcv;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** PopularityChecker.isVolumeSpike 검증. 해성디에스 실측 사례(평소 대비 5~10배) 기준. */
class PopularityCheckerTest {

    private static List<DailyOhlcv> flatVolumeSeries(int days, long normalVolume, long latestVolume) {
        List<DailyOhlcv> rows = new ArrayList<>();
        LocalDate start = LocalDate.now().minusDays(days);
        for (int i = 0; i < days; i++) {
            long vol = (i == days - 1) ? latestVolume : normalVolume;
            rows.add(new DailyOhlcv("195870", start.plusDays(i), BigDecimal.TEN, BigDecimal.TEN,
                    BigDecimal.TEN, BigDecimal.TEN, vol));
        }
        return rows;
    }

    @Test
    void spike_above_threshold_detected() {
        List<DailyOhlcv> rows = flatVolumeSeries(21, 100_000, 500_000); // 5배
        assertThat(PopularityChecker.isVolumeSpike(rows, 20, 3.0)).isTrue();
    }

    @Test
    void normal_volume_not_flagged() {
        List<DailyOhlcv> rows = flatVolumeSeries(21, 100_000, 150_000); // 1.5배
        assertThat(PopularityChecker.isVolumeSpike(rows, 20, 3.0)).isFalse();
    }

    @Test
    void insufficient_history_returns_false() {
        List<DailyOhlcv> rows = flatVolumeSeries(5, 100_000, 500_000); // window+1 미달
        assertThat(PopularityChecker.isVolumeSpike(rows, 20, 3.0)).isFalse();
    }
}
