package com.cloudhandson.tossstock.reentry;

import java.math.BigDecimal;
import java.math.MathContext;

/** 재진입 알림 단계 판정(순수 함수). 설계: docs/design/reentry-alert/fn-decideStage.md */
public final class ReentryStageCalculator {

    private ReentryStageCalculator() {
    }

    public static ReentryStage decide(BigDecimal current, BigDecimal reference,
                                       double stage1Pct, double stage2Pct,
                                       boolean stage1Done, boolean stage2Done) {
        if (reference == null || reference.signum() <= 0) {
            throw new IllegalArgumentException("reference must be > 0: " + reference);
        }
        double pct = current.subtract(reference)
                .divide(reference, MathContext.DECIMAL64)
                .doubleValue() * 100;

        if (pct >= stage2Pct && !stage2Done) {
            return ReentryStage.STAGE2;
        }
        if (pct >= stage1Pct && !stage1Done) {
            return ReentryStage.STAGE1;
        }
        return ReentryStage.NONE;
    }
}
