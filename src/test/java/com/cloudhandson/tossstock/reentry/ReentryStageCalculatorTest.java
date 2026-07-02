package com.cloudhandson.tossstock.reentry;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** ReentryStageCalculator.decide 검증. 설계: docs/design/reentry-alert/fn-decideStage.md */
class ReentryStageCalculatorTest {

    private static final BigDecimal REF = BigDecimal.valueOf(288_000);

    @Test
    void below_stage1_returns_none() {
        BigDecimal current = BigDecimal.valueOf(288_000 * 1.03); // +3%
        assertThat(ReentryStageCalculator.decide(current, REF, 5, 10, false, false))
                .isEqualTo(ReentryStage.NONE);
    }

    @Test
    void exactly_stage1_threshold_triggers_stage1() {
        BigDecimal current = BigDecimal.valueOf(288_000 * 1.05); // +5.0%
        assertThat(ReentryStageCalculator.decide(current, REF, 5, 10, false, false))
                .isEqualTo(ReentryStage.STAGE1);
    }

    @Test
    void exactly_stage2_threshold_triggers_stage2() {
        BigDecimal current = BigDecimal.valueOf(288_000 * 1.10); // +10.0%
        assertThat(ReentryStageCalculator.decide(current, REF, 5, 10, false, false))
                .isEqualTo(ReentryStage.STAGE2);
    }

    @Test
    void stage1_already_sent_does_not_repeat() {
        BigDecimal current = BigDecimal.valueOf(288_000 * 1.07); // +7%
        assertThat(ReentryStageCalculator.decide(current, REF, 5, 10, true, false))
                .isEqualTo(ReentryStage.NONE);
    }

    @Test
    void stage2_already_sent_never_refires() {
        BigDecimal current = BigDecimal.valueOf(288_000 * 1.30); // +30%
        assertThat(ReentryStageCalculator.decide(current, REF, 5, 10, true, true))
                .isEqualTo(ReentryStage.NONE);
    }

    @Test
    void jumps_straight_to_stage2_when_stage1_already_sent() {
        BigDecimal current = BigDecimal.valueOf(288_000 * 1.12); // +12%
        assertThat(ReentryStageCalculator.decide(current, REF, 5, 10, true, false))
                .isEqualTo(ReentryStage.STAGE2);
    }

    @Test
    void non_positive_reference_throws() {
        assertThatThrownBy(() -> ReentryStageCalculator.decide(BigDecimal.TEN, BigDecimal.ZERO, 5, 10, false, false))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
