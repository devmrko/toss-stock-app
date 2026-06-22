package com.cloudhandson.tossstock.toss;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TossTokenTest {

    @Test
    void expired_when_within_60s_window() {
        TossToken token = new TossToken("t", "Bearer", 1000);
        // 만료 여유 60초: exp-60 = 940
        assertThat(token.isExpired(940)).isTrue();   // 경계: 정확히 60초 전 → 만료 취급
        assertThat(token.isExpired(1000)).isTrue();  // 이미 만료
    }

    @Test
    void valid_when_more_than_60s_remaining() {
        TossToken token = new TossToken("t", "Bearer", 1000);
        assertThat(token.isExpired(939)).isFalse();  // 61초 남음 → 유효
    }
}
