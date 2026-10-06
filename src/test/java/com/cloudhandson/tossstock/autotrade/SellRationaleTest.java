package com.cloudhandson.tossstock.autotrade;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 매도 결정근거 문자열 검증 — "판 결정이 모호하다"는 피드백이 출발점이라 어느 스탑이 바인딩이었는지,
 * 진입/피크 대비 수익률이 모두 남는지 본다.
 * 설계: docs/design/832-decision-rationale-logging/README.md §10
 */
class SellRationaleTest {

    @Test
    void trail_stop_shows_peak_drawdown_and_binding_stop() {
        String s = SellRationale.describe(ExitReason.TRAIL_STOP,
                BigDecimal.valueOf(200_000), BigDecimal.valueOf(235_000), BigDecimal.valueOf(210_500),
                TrailingStopCalculator.hardFloor(BigDecimal.valueOf(200_000), 10),
                TrailingStopCalculator.trailFloor(BigDecimal.valueOf(235_000), 10));

        assertThat(s).isEqualTo("트레일스탑(피크235000→현재210500,-10.43%) 진입200000 수익+5.25% "
                + "스탑211500(하드180000/트레일211500)");
    }

    @Test
    void hard_stop_reports_hard_floor_as_binding() {
        String s = SellRationale.describe(ExitReason.HARD_STOP,
                BigDecimal.valueOf(200_000), BigDecimal.valueOf(205_000), BigDecimal.valueOf(179_000),
                TrailingStopCalculator.hardFloor(BigDecimal.valueOf(200_000), 10),
                TrailingStopCalculator.trailFloor(BigDecimal.valueOf(205_000), 10));

        assertThat(s).startsWith("하드스탑(피크205000→현재179000,-12.68%)")
                .contains("진입200000 수익-10.5%")
                .contains("스탑184500(하드180000/트레일184500)");
    }

    @Test
    void news_faded_is_labeled_even_though_no_stop_was_hit() {
        String s = SellRationale.describe(ExitReason.NEWS_FADED,
                BigDecimal.valueOf(100_000), BigDecimal.valueOf(120_000), BigDecimal.valueOf(118_000),
                BigDecimal.valueOf(90_000), BigDecimal.valueOf(108_000));

        assertThat(s).startsWith("뉴스소멸(")
                .contains("진입100000 수익+18%")
                .contains("스탑108000(하드90000/트레일108000)");
    }

    @Test
    void null_inputs_render_as_na_without_throwing() {
        String s = SellRationale.describe(null, null, null, null, null, null);

        assertThat(s).isEqualTo("매도(피크N/A→현재N/A,N/A) 진입N/A 수익N/A 스탑N/A");
    }

    @Test
    void zero_entry_price_does_not_divide_by_zero() {
        String s = SellRationale.describe(ExitReason.MANUAL, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.valueOf(1_000), null, BigDecimal.valueOf(900));

        assertThat(s).contains("수익N/A").contains("스탑900(하드N/A/트레일900)");
    }
}
