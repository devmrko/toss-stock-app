package com.cloudhandson.tossstock.market;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #885 미완성 바 판정. 설계: docs/design/885-us-partial-bar/fn-sessionClosed.md §10
 */
class BarCompletenessTest {

    private static final LocalDate THU = LocalDate.of(2026, 10, 8);

    // ---- KR ----

    @Test
    void kr_마감_전은_미완성이다() {
        assertThat(BarCompleteness.sessionClosed("KR", THU, THU.atTime(15, 29))).isFalse();
        assertThat(BarCompleteness.sessionClosed("KR", THU, THU.atTime(9, 0))).isFalse();
    }

    @Test
    void kr_마감_시각부터_완성이다() {
        // 경계 포함. 정기 수집(15:40)이 통과해야 한다 — 회귀 방지의 핵심.
        assertThat(BarCompleteness.sessionClosed("KR", THU, THU.atTime(15, 30))).isTrue();
        assertThat(BarCompleteness.sessionClosed("KR", THU, THU.atTime(15, 40))).isTrue();
    }

    @Test
    void kr_과거_거래일은_완성이다() {
        assertThat(BarCompleteness.sessionClosed("KR", THU, THU.plusDays(1).atTime(9, 0))).isTrue();
        assertThat(BarCompleteness.sessionClosed("KR", THU, THU.plusYears(1).atStartOfDay())).isTrue();
    }

    // ---- US ----

    @Test
    void us_장중에는_그날_바가_미완성이다() {
        // 미국장 10-08(ET)은 KST 10-08 22:30 ~ 10-09 05:00 에 열린다.
        assertThat(BarCompleteness.sessionClosed("US", THU, THU.atTime(23, 0))).isFalse();
        assertThat(BarCompleteness.sessionClosed("US", THU, THU.plusDays(1).atTime(3, 0))).isFalse();
    }

    @Test
    void us_정기수집_시각에_그날_바는_미완성이다() {
        // 버그의 정체: KST 15:40 은 ET 02:40(프리마켓)이다.
        assertThat(BarCompleteness.sessionClosed("US", THU, THU.atTime(15, 40))).isFalse();
    }

    @Test
    void us_다음날_07시부터_완성이다() {
        // 16:00 ET 마감의 KST 환산은 05:00~06:00. 07:00 이 두 경우를 모두 덮는다.
        assertThat(BarCompleteness.sessionClosed("US", THU, THU.plusDays(1).atTime(6, 59))).isFalse();
        assertThat(BarCompleteness.sessionClosed("US", THU, THU.plusDays(1).atTime(7, 0))).isTrue();
    }

    @Test
    void us_전일_바는_정기수집_시각에_완성이다() {
        // 10-09 15:40 수집에서 10-08 바는 완성값으로 덮어써져야 한다(기존 미완성 바 정정).
        assertThat(BarCompleteness.sessionClosed("US", THU, THU.plusDays(1).atTime(15, 40))).isTrue();
    }

    @Test
    void us_금요일_바는_토요일_아침에_완성이다() {
        LocalDate fri = LocalDate.of(2026, 10, 9);
        assertThat(BarCompleteness.sessionClosed("US", fri, fri.plusDays(1).atTime(7, 0))).isTrue();
    }

    // ---- fail-closed / 시장 판정 ----

    @Test
    void 판정_불가는_저장하지_않는다() {
        assertThat(BarCompleteness.sessionClosed("KR", null, THU.atTime(16, 0))).isFalse();
        assertThat(BarCompleteness.sessionClosed("US", THU, null)).isFalse();
        assertThat(BarCompleteness.sessionClosed(null, null, null)).isFalse();
    }

    @Test
    void 미래_거래일은_저장하지_않는다() {
        assertThat(BarCompleteness.sessionClosed("KR", THU.plusDays(1), THU.atTime(16, 0))).isFalse();
    }

    @Test
    void 시장이_미지면_us_규칙을_쓴다() {
        // 보수적(더 늦게 인정) 쪽으로 기운다.
        assertThat(BarCompleteness.sessionClosed(null, THU, THU.atTime(16, 0))).isFalse();
        assertThat(BarCompleteness.sessionClosed("XX", THU, THU.atTime(16, 0))).isFalse();
        assertThat(BarCompleteness.sessionClosed(null, THU, THU.plusDays(1).atTime(7, 0))).isTrue();
    }

    @Test
    void 종목코드로_시장을_가른다() {
        assertThat(BarCompleteness.marketOf("005930")).isEqualTo("KR");
        assertThat(BarCompleteness.marketOf("069500")).isEqualTo("KR");
        assertThat(BarCompleteness.marketOf("TSM")).isEqualTo("US");
        assertThat(BarCompleteness.marketOf("GOOGL")).isEqualTo("US");
        assertThat(BarCompleteness.marketOf(null)).isEqualTo("US");
    }
}
