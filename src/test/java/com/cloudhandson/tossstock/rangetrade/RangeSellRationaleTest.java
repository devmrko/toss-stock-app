package com.cloudhandson.tossstock.rangetrade;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static com.cloudhandson.tossstock.rangetrade.RangeTradeTestFixtures.props;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 레인지 매도 결정근거 문자열 검증 — 어느 임계선(익절선/손절선)이 발동했는지와 진입 대비 수익률.
 * 설계: docs/design/832-decision-rationale-logging/README.md §3, §10
 */
class RangeSellRationaleTest {

    private static final BigDecimal LOW = BigDecimal.valueOf(100_000);
    private static final BigDecimal HIGH = BigDecimal.valueOf(130_000);
    private static final BigDecimal ENTRY = BigDecimal.valueOf(105_000);
    private static final BigDecimal PROFIT_FLOOR = RangeTradeSignal.profitFloor(HIGH, props(true));   // 117,000
    private static final BigDecimal BREAKDOWN_FLOOR = RangeTradeSignal.breakdownFloor(LOW, props(true)); // 95,000

    @Test
    void profit_take_highlights_profit_floor_and_pnl() {
        String s = RangeSellRationale.describe(RangeExitReason.PROFIT_TAKE, ENTRY, LOW, HIGH,
                PROFIT_FLOOR, BREAKDOWN_FLOOR, BigDecimal.valueOf(120_000));

        assertThat(s).isEqualTo("익절(익절선117000) 현재120000 진입105000 수익+14.29% "
                + "밴드100000~130000 익절선117000/손절선95000");
    }

    @Test
    void breakdown_highlights_breakdown_floor_and_loss() {
        String s = RangeSellRationale.describe(RangeExitReason.RANGE_BREAKDOWN, ENTRY, LOW, HIGH,
                PROFIT_FLOOR, BREAKDOWN_FLOOR, BigDecimal.valueOf(94_000));

        assertThat(s).startsWith("밴드이탈손절(손절선95000)")
                .contains("수익-10.48%")
                .contains("익절선117000/손절선95000");
    }

    @Test
    void bad_news_sell_is_price_independent() {
        String s = RangeSellRationale.describe(RangeExitReason.BAD_NEWS, ENTRY, LOW, HIGH,
                PROFIT_FLOOR, BREAKDOWN_FLOOR, BigDecimal.valueOf(110_000));

        assertThat(s).startsWith("악재매도(가격무관)").contains("수익+4.76%");
    }

    @Test
    void null_inputs_render_as_na_without_throwing() {
        String s = RangeSellRationale.describe(null, null, null, null, null, null, null);

        assertThat(s).isEqualTo("매도(가격무관) 현재N/A 진입N/A 수익N/A 밴드N/A~N/A 익절선N/A/손절선N/A");
    }

    @Test
    void zero_entry_price_does_not_divide_by_zero() {
        String s = RangeSellRationale.describe(RangeExitReason.PROFIT_TAKE, BigDecimal.ZERO, LOW, HIGH,
                PROFIT_FLOOR, BREAKDOWN_FLOOR, BigDecimal.valueOf(120_000));

        assertThat(s).contains("수익N/A");
    }
}
