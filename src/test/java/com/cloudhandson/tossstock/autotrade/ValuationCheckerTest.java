package com.cloudhandson.tossstock.autotrade;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/** ValuationChecker.isUndervalued 검증. 설계: docs/design/808-auto-trade-engine/fn-valuation-client.md */
class ValuationCheckerTest {

    @Test
    void both_under_threshold_passes() {
        Valuation v = new Valuation(BigDecimal.valueOf(15.5), BigDecimal.valueOf(1.44));
        assertThat(ValuationChecker.isUndervalued(v, 20.0, 2.0)).isTrue();
    }

    @Test
    void per_over_threshold_fails() {
        Valuation v = new Valuation(BigDecimal.valueOf(38.6), BigDecimal.valueOf(1.0));
        assertThat(ValuationChecker.isUndervalued(v, 20.0, 2.0)).isFalse();
    }

    @Test
    void pbr_over_threshold_fails() {
        Valuation v = new Valuation(BigDecimal.valueOf(12.8), BigDecimal.valueOf(3.33));
        assertThat(ValuationChecker.isUndervalued(v, 20.0, 2.0)).isFalse();
    }

    @Test
    void exactly_at_threshold_passes() {
        Valuation v = new Valuation(BigDecimal.valueOf(20.0), BigDecimal.valueOf(2.0));
        assertThat(ValuationChecker.isUndervalued(v, 20.0, 2.0)).isTrue();
    }

    @Test
    void null_valuation_fails_closed() {
        assertThat(ValuationChecker.isUndervalued(null, 20.0, 2.0)).isFalse();
    }

    @Test
    void null_per_or_pbr_fails_closed() {
        assertThat(ValuationChecker.isUndervalued(new Valuation(null, BigDecimal.ONE), 20.0, 2.0)).isFalse();
        assertThat(ValuationChecker.isUndervalued(new Valuation(BigDecimal.ONE, null), 20.0, 2.0)).isFalse();
    }

    @Test
    void negative_per_fails_closed() {
        Valuation v = new Valuation(BigDecimal.valueOf(-5.0), BigDecimal.valueOf(1.0));
        assertThat(ValuationChecker.isUndervalued(v, 20.0, 2.0)).isFalse();
    }

    // --- #855 촉매 면제 상한(withinCatalystBound) ---

    @Test
    void 상한_이내면_촉매_면제_허용() {
        // LG전자 실측(PER 36.7/PBR 1.42) — 저평가(30/3)는 아니지만 상한(90/9) 이내라 면제 유지
        Valuation v = new Valuation(BigDecimal.valueOf(36.7), BigDecimal.valueOf(1.42));
        assertThat(ValuationChecker.withinCatalystBound(v, 90.0, 9.0)).isTrue();
    }

    @Test
    void PER_상한_초과면_촉매_면제_거부() {
        // 포스코퓨처엠 실측(PER 392.49/PBR 4.21) — "6조 수주" 촉매가 있어도 면제 불가
        Valuation v = new Valuation(BigDecimal.valueOf(392.49), BigDecimal.valueOf(4.21));
        assertThat(ValuationChecker.withinCatalystBound(v, 90.0, 9.0)).isFalse();
    }

    @Test
    void PBR_상한_초과면_촉매_면제_거부() {
        Valuation v = new Valuation(BigDecimal.valueOf(10.0), BigDecimal.valueOf(12.0));
        assertThat(ValuationChecker.withinCatalystBound(v, 90.0, 9.0)).isFalse();
    }

    @Test
    void PER_데이터없으면_촉매_면제_거부() {
        // 삼성SDI 실측(PER N/A) — 재평가를 논할 근거가 없으므로 fail-closed
        Valuation v = new Valuation(null, BigDecimal.valueOf(1.84));
        assertThat(ValuationChecker.withinCatalystBound(v, 90.0, 9.0)).isFalse();
        assertThat(ValuationChecker.withinCatalystBound(null, 90.0, 9.0)).isFalse();
    }

    @Test
    void 적자_PER음수면_촉매_면제_거부() {
        Valuation v = new Valuation(BigDecimal.valueOf(-12.0), BigDecimal.valueOf(1.0));
        assertThat(ValuationChecker.withinCatalystBound(v, 90.0, 9.0)).isFalse();
    }
}
