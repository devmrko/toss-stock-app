package com.cloudhandson.tossstock.autotrade;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MarketHours.isOpen 검증 — 특히 미국장이 자정을 넘어가며 요일이 바뀌는 경계.
 * 2026-09-28: MON=9/28, TUE=9/29, FRI=10/2, SAT=10/3, SUN=10/4 기준.
 */
class MarketHoursTest {

    @Test
    void kr_open_during_regular_hours_on_weekday() {
        assertThat(MarketHours.isOpen("KR", LocalDateTime.of(2026, 9, 28, 10, 0))).isTrue();
    }

    @Test
    void kr_closed_before_open_and_after_close() {
        assertThat(MarketHours.isOpen("KR", LocalDateTime.of(2026, 9, 28, 8, 59))).isFalse();
        assertThat(MarketHours.isOpen("KR", LocalDateTime.of(2026, 9, 28, 15, 30))).isFalse();
    }

    @Test
    void kr_closed_on_weekend() {
        assertThat(MarketHours.isOpen("KR", LocalDateTime.of(2026, 10, 3, 10, 0))).isFalse(); // SAT
    }

    @Test
    void us_open_monday_evening_kst() {
        assertThat(MarketHours.isOpen("US", LocalDateTime.of(2026, 9, 28, 23, 0))).isTrue(); // MON 23:00
    }

    @Test
    void us_open_tuesday_early_morning_is_monday_session_tail() {
        assertThat(MarketHours.isOpen("US", LocalDateTime.of(2026, 9, 29, 2, 0))).isTrue(); // TUE 02:00 = MON 세션 연장
    }

    @Test
    void us_closed_monday_early_morning_sunday_session_does_not_exist() {
        assertThat(MarketHours.isOpen("US", LocalDateTime.of(2026, 9, 28, 2, 0))).isFalse(); // MON 02:00 = SUN 밤(장 없음)
    }

    @Test
    void us_open_saturday_early_morning_friday_session_tail() {
        assertThat(MarketHours.isOpen("US", LocalDateTime.of(2026, 10, 3, 2, 0))).isTrue(); // SAT 02:00 = FRI 세션 연장
    }

    @Test
    void us_closed_saturday_evening_no_session() {
        assertThat(MarketHours.isOpen("US", LocalDateTime.of(2026, 10, 3, 23, 0))).isFalse(); // SAT 23:00
    }

    @Test
    void us_closed_during_kr_daytime() {
        assertThat(MarketHours.isOpen("US", LocalDateTime.of(2026, 9, 28, 14, 0))).isFalse(); // MON 14:00
    }

    @Test
    void us_boundary_exactly_at_open_and_close() {
        assertThat(MarketHours.isOpen("US", LocalDateTime.of(2026, 9, 28, 22, 30))).isTrue();  // 정각 개장
        assertThat(MarketHours.isOpen("US", LocalDateTime.of(2026, 9, 29, 5, 0))).isFalse();   // 정각 마감
    }
}
