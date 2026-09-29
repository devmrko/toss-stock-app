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

    private static List<DailyOhlcv> withPriceMove(double prevClose, double latestClose) {
        LocalDate d0 = LocalDate.now().minusDays(1);
        return List.of(
                new DailyOhlcv("009150", d0, BigDecimal.valueOf(prevClose), BigDecimal.valueOf(prevClose),
                        BigDecimal.valueOf(prevClose), BigDecimal.valueOf(prevClose), 1_000_000L),
                new DailyOhlcv("009150", d0.plusDays(1), BigDecimal.valueOf(latestClose), BigDecimal.valueOf(latestClose),
                        BigDecimal.valueOf(latestClose), BigDecimal.valueOf(latestClose), 900_000L));
    }

    @Test
    void large_cap_low_volume_ratio_but_real_price_move_is_popular() {
        // 삼성전기 사례: 거래량비 0.90배(탈락)지만 가격 +2.55%
        List<DailyOhlcv> rows = withPriceMove(1_490_000, 1_528_000);
        assertThat(PopularityChecker.isVolumeSpike(rows, 1, 1.2)).isFalse();
        assertThat(PopularityChecker.isPriceMoveSignificant(rows, 2.0)).isTrue();
        assertThat(PopularityChecker.isPopular(rows, 1, 1.2, 2.0)).isTrue();
    }

    @Test
    void small_price_move_without_volume_spike_not_popular() {
        List<DailyOhlcv> rows = withPriceMove(100_000, 100_500); // +0.5%
        assertThat(PopularityChecker.isPopular(rows, 1, 1.2, 2.0)).isFalse();
    }

    @Test
    void negative_price_move_not_significant() {
        List<DailyOhlcv> rows = withPriceMove(100_000, 90_000); // -10%, 하락은 호재 인기로 인정 안 함
        assertThat(PopularityChecker.isPriceMoveSignificant(rows, 2.0)).isFalse();
    }

    @Test
    void exactly_at_price_threshold_counts() {
        List<DailyOhlcv> rows = withPriceMove(100_000, 102_000); // 정확히 +2.0%
        assertThat(PopularityChecker.isPriceMoveSignificant(rows, 2.0)).isTrue();
    }
}
