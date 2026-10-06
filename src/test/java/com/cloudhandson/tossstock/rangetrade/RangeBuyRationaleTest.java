package com.cloudhandson.tossstock.rangetrade;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static com.cloudhandson.tossstock.rangetrade.RangeTradeTestFixtures.props;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 레인지 매수 결정근거 문자열 검증 — 밴드 하단/상단, 매수상한선, 밴드 내 위치(%)가 남는지.
 * 설계: docs/design/832-decision-rationale-logging/README.md §3, §10
 */
class RangeBuyRationaleTest {

    private static final BigDecimal LOW = BigDecimal.valueOf(100_000);
    private static final BigDecimal HIGH = BigDecimal.valueOf(130_000);

    @Test
    void contains_band_entry_ceiling_and_position_in_band() {
        String s = RangeBuyRationale.describe(LOW, HIGH,
                RangeTradeSignal.entryCeiling(LOW, props(true)), BigDecimal.valueOf(104_000));

        // entryZonePct=10% → 진입상한 = 100,000 × 1.10
        assertThat(s).isEqualTo("밴드100000~130000 진입상한110000 매수가104000(밴드내13.33%)");
    }

    @Test
    void price_at_band_bottom_is_zero_percent() {
        String s = RangeBuyRationale.describe(LOW, HIGH,
                RangeTradeSignal.entryCeiling(LOW, props(true)), LOW);

        assertThat(s).contains("(밴드내0%)");
    }

    @Test
    void null_inputs_render_as_na_without_throwing() {
        String s = RangeBuyRationale.describe(null, null, null, null);

        assertThat(s).isEqualTo("밴드N/A~N/A 진입상한N/A 매수가N/A(밴드내N/A)");
    }

    @Test
    void zero_width_band_does_not_divide_by_zero() {
        String s = RangeBuyRationale.describe(LOW, LOW, LOW, LOW);

        assertThat(s).contains("(밴드내N/A)");
    }
}
