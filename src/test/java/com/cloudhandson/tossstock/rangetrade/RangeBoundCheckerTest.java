package com.cloudhandson.tossstock.rangetrade;

import com.cloudhandson.tossstock.market.DailyOhlcv;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static com.cloudhandson.tossstock.rangetrade.RangeTradeTestFixtures.bar;
import static com.cloudhandson.tossstock.rangetrade.RangeTradeTestFixtures.bars;
import static com.cloudhandson.tossstock.rangetrade.RangeTradeTestFixtures.oscillating;
import static com.cloudhandson.tossstock.rangetrade.RangeTradeTestFixtures.props;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 박스권 판정 검증 — 설계: docs/design/818-range-trade-swing/fn-range-bound-checker.md §10.
 * 모든 케이스는 운영 파라미터(window 60 / width 26~50% / drift 15%)를 그대로 쓴다.
 */
class RangeBoundCheckerTest {

    private static final int BARS = 61; // windowDays(60) + 1

    @Test
    void oscillating_band_is_range_bound_with_exact_band_values() {
        // 100↔130 왕복: 폭 (130-100)/115*100 = 26.09% ≥ 26, 드리프트 ≈ 0.
        RangeBoundChecker.Result r = RangeBoundChecker.evaluate(oscillating(BARS, 100, 130), props());

        assertThat(r.isRangeBound()).isTrue();
        assertThat(r.low()).isEqualByComparingTo(BigDecimal.valueOf(100));
        assertThat(r.high()).isEqualByComparingTo(BigDecimal.valueOf(130));
    }

    @Test
    void order_of_input_does_not_matter() {
        List<DailyOhlcv> shuffled = new ArrayList<>(oscillating(BARS, 100, 130));
        Collections.reverse(shuffled);

        assertThat(RangeBoundChecker.evaluate(shuffled, props()).isRangeBound()).isTrue();
    }

    @Test
    void persistent_downtrend_is_rejected_by_trend_drift() {
        // 130 → 90 지속 하락: 폭은 36%로 범위 안이지만 전반부/후반부 종가 평균차가 15%를 넘음.
        List<DailyOhlcv> declining = bars(BARS, (i, date) -> {
            double close = 130 - i * (40.0 / (BARS - 1));
            return bar(date, close + 0.5, close - 0.5, close);
        });

        assertThat(RangeBoundChecker.evaluate(declining, props()).isRangeBound()).isFalse();
    }

    @Test
    void too_narrow_band_is_rejected() {
        assertThat(RangeBoundChecker.evaluate(oscillating(BARS, 100, 102), props()).isRangeBound()).isFalse();
    }

    @Test
    void band_narrower_than_round_trip_cost_target_is_rejected() {
        // 100↔120(18.2%)은 Ellman ROO 역산(26%) 미달 — 왕복비용+목표수익이 안 남으므로 탈락.
        assertThat(RangeBoundChecker.evaluate(oscillating(BARS, 100, 120), props()).isRangeBound()).isFalse();
    }

    @Test
    void too_wide_band_is_rejected() {
        assertThat(RangeBoundChecker.evaluate(oscillating(BARS, 100, 200), props()).isRangeBound()).isFalse();
    }

    @Test
    void insufficient_data_is_rejected() {
        assertThat(RangeBoundChecker.evaluate(oscillating(BARS - 1, 100, 130), props()).isRangeBound()).isFalse();
        assertThat(RangeBoundChecker.evaluate(List.of(), props()).isRangeBound()).isFalse();
        assertThat(RangeBoundChecker.evaluate(null, props()).isRangeBound()).isFalse();
    }

    @Test
    void bars_with_null_prices_are_excluded_and_can_cause_data_shortage() {
        List<DailyOhlcv> withNulls = new ArrayList<>(oscillating(BARS, 100, 130));
        withNulls.get(0).setHighP(null);
        withNulls.get(1).setLowP(null);
        withNulls.get(2).setCloseP(null);

        // 61개 중 3개 제외 → 58개로 윈도우 미달 → fail-closed
        assertThat(RangeBoundChecker.evaluate(withNulls, props()).isRangeBound()).isFalse();
    }

    @Test
    void width_and_drift_exactly_on_threshold_pass() {
        // 폭 정확히 26.0%: low=87, high=113 → 26/((87+113)/2)*100 = 26.0
        // 드리프트 정확히 15.0%: 전반부(31개) 종가 92.5, 후반부(30개) 107.5 → 15/100*100 = 15.0
        List<DailyOhlcv> boundary = bars(BARS, (i, date) -> bar(date, 113, 87, i < (BARS + 1) / 2 ? 92.5 : 107.5));

        RangeBoundChecker.Result r = RangeBoundChecker.evaluate(boundary, props());

        assertThat(r.isRangeBound()).isTrue(); // 경계값은 포함(<=/>=)
        assertThat(r.low()).isEqualByComparingTo(BigDecimal.valueOf(87));
        assertThat(r.high()).isEqualByComparingTo(BigDecimal.valueOf(113));
    }

    @Test
    void odd_window_puts_middle_bar_in_first_half() {
        // 홀수(61)개 윈도우에서 중간 1개를 전반부에 넣는다는 규칙(§7) 확인:
        // 중간 바의 종가를 후반부 쪽 값으로 바꾸면 전반부 평균이 올라가 드리프트가 15% 아래로 내려간다.
        List<DailyOhlcv> shifted = bars(BARS, (i, date) -> bar(date, 113, 87, i < (BARS - 1) / 2 ? 92.5 : 107.5));

        // 전반부 31개 = 92.5×30 + 107.5×1 → 평균 ≈ 92.98, 후반부 30개 = 107.5 → 드리프트 ≈ 14.5% < 15%
        assertThat(RangeBoundChecker.evaluate(shifted, props()).isRangeBound()).isTrue();
    }

    @Test
    void flat_price_has_zero_width_and_is_rejected() {
        List<DailyOhlcv> flat = bars(BARS, (i, date) -> bar(date, 100, 100, 100));

        assertThat(RangeBoundChecker.evaluate(flat, props()).isRangeBound()).isFalse();
    }

    @Test
    void evaluate_does_not_mutate_input_order() {
        List<DailyOhlcv> input = new ArrayList<>(oscillating(BARS, 100, 130));
        Collections.reverse(input);
        LocalDate firstBefore = input.get(0).getTradeDate();

        RangeBoundChecker.evaluate(input, props());

        assertThat(input.get(0).getTradeDate()).isEqualTo(firstBefore); // 순수 함수 — 입력 보존
    }
}
