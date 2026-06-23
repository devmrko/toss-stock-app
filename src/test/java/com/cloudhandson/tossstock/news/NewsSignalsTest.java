package com.cloudhandson.tossstock.news;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class NewsSignalsTest {

    @Test
    void aggregate_keeps_strongest_per_key() {
        Map<String, String> m = NewsSignals.aggregate(List.of(
                "005930:S2,반도체:S4",
                "005930:S5,MARKET:S1",   // 005930 더 강함(S5)
                "반도체:S3"));            // 반도체 약함 → 유지 S4
        assertThat(m).containsEntry("005930", "S5")
                .containsEntry("반도체", "S4")
                .containsEntry("MARKET", "S1");
    }

    @Test
    void empty_and_null_safe() {
        assertThat(NewsSignals.aggregate(null)).isEmpty();
        assertThat(NewsSignals.aggregate(List.of())).isEmpty();
    }

    @Test
    void ignores_malformed_tokens() {
        assertThat(NewsSignals.aggregate(List.of("foo", "005930:X", "005930", ":S5", ""))).isEmpty();
    }
}
