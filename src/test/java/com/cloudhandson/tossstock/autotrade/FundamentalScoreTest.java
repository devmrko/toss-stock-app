package com.cloudhandson.tossstock.autotrade;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** FundamentalScore 검증 — "대부분 YES"(전부는 아님) 채점. */
class FundamentalScoreTest {

    @Test
    void all_five_pass_counts_five() {
        FundamentalScore s = new FundamentalScore(true, true, true, true, true);
        assertThat(s.passCount()).isEqualTo(5);
        assertThat(s.passes(4)).isTrue();
    }

    @Test
    void four_of_five_passes_default_threshold() {
        FundamentalScore s = new FundamentalScore(true, true, true, true, false);
        assertThat(s.passCount()).isEqualTo(4);
        assertThat(s.passes(4)).isTrue();
    }

    @Test
    void three_of_five_fails_default_threshold() {
        FundamentalScore s = new FundamentalScore(true, true, true, false, false);
        assertThat(s.passCount()).isEqualTo(3);
        assertThat(s.passes(4)).isFalse();
    }

    @Test
    void zero_pass_count_when_all_false() {
        FundamentalScore s = new FundamentalScore(false, false, false, false, false);
        assertThat(s.passCount()).isEqualTo(0);
    }
}
