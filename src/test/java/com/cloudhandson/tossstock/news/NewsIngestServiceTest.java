package com.cloudhandson.tossstock.news;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class NewsIngestServiceTest {

    @Test
    void ttl_strong_24h_weak_8h_neutral_expired() {
        LocalDateTime now = LocalDateTime.now();
        assertThat(NewsIngestService.ttl(2)).isAfter(now.plusHours(23)).isBefore(now.plusHours(25));
        assertThat(NewsIngestService.ttl(1)).isAfter(now.plusHours(7)).isBefore(now.plusHours(9));
        assertThat(NewsIngestService.ttl(0)).isBeforeOrEqualTo(now);   // S3 즉시 만료
    }
}
