package com.cloudhandson.tossstock.autotrade;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** EarningsQualityChecker.hasThreeYearUptrend 검증. 설계: docs/design/808-auto-trade-engine/fn-fundamental-checklist.md */
class EarningsQualityCheckerTest {

    private static AnnualFinancials.Year year(String label, long rev, long op, long net) {
        return new AnnualFinancials.Year(label, BigDecimal.valueOf(rev), BigDecimal.valueOf(op),
                BigDecimal.valueOf(net), BigDecimal.TEN, BigDecimal.ONE);
    }

    @Test
    void three_year_uptrend_with_positive_operating_profit_passes() {
        AnnualFinancials f = new AnnualFinancials(List.of(
                year("2023", 100, 10, 8), year("2024", 120, 15, 12), year("2025", 150, 20, 16)));
        assertThat(EarningsQualityChecker.hasThreeYearUptrend(f)).isTrue();
    }

    @Test
    void declining_revenue_fails() {
        AnnualFinancials f = new AnnualFinancials(List.of(
                year("2023", 150, 10, 8), year("2024", 120, 15, 12), year("2025", 100, 20, 16)));
        assertThat(EarningsQualityChecker.hasThreeYearUptrend(f)).isFalse();
    }

    @Test
    void latest_year_operating_loss_fails_even_if_trend_up() {
        AnnualFinancials f = new AnnualFinancials(List.of(
                year("2023", 100, -20, -15), year("2024", 120, -10, -8), year("2025", 150, -5, -3)));
        assertThat(EarningsQualityChecker.hasThreeYearUptrend(f)).isFalse();
    }

    @Test
    void fewer_than_three_years_fails_closed() {
        AnnualFinancials f = new AnnualFinancials(List.of(year("2024", 120, 15, 12), year("2025", 150, 20, 16)));
        assertThat(EarningsQualityChecker.hasThreeYearUptrend(f)).isFalse();
    }

    @Test
    void null_financials_fails_closed() {
        assertThat(EarningsQualityChecker.hasThreeYearUptrend(null)).isFalse();
    }

    @Test
    void flat_revenue_still_passes_non_decreasing() {
        AnnualFinancials f = new AnnualFinancials(List.of(
                year("2023", 100, 10, 8), year("2024", 100, 15, 12), year("2025", 100, 20, 16)));
        assertThat(EarningsQualityChecker.hasThreeYearUptrend(f)).isTrue();
    }

    private static AnnualFinancials.Year yearNoOperatingProfit(String label, long rev, long net) {
        return new AnnualFinancials.Year(label, BigDecimal.valueOf(rev), null,
                BigDecimal.valueOf(net), null, null);
    }

    /** 2026-09-30: 야후(US) 무료 API 실측 — 영업이익 항상 null(LOCO 등). 매출+순이익만으로 대체 판정. */
    @Test
    void us_style_missing_operating_profit_falls_back_to_revenue_and_net_income() {
        AnnualFinancials f = new AnnualFinancials(List.of(
                yearNoOperatingProfit("2023", 468664000, 25554000),
                yearNoOperatingProfit("2024", 473008000, 25684000),
                yearNoOperatingProfit("2025", 490046000, 26486000)));
        assertThat(EarningsQualityChecker.hasThreeYearUptrend(f)).isTrue();
    }

    @Test
    void us_style_missing_operating_profit_but_net_income_declining_fails() {
        AnnualFinancials f = new AnnualFinancials(List.of(
                yearNoOperatingProfit("2023", 100, 30),
                yearNoOperatingProfit("2024", 120, 20),
                yearNoOperatingProfit("2025", 150, 10)));
        assertThat(EarningsQualityChecker.hasThreeYearUptrend(f)).isFalse();
    }

    @Test
    void us_style_missing_operating_profit_but_net_loss_fails() {
        AnnualFinancials f = new AnnualFinancials(List.of(
                yearNoOperatingProfit("2023", 100, -30),
                yearNoOperatingProfit("2024", 120, -20),
                yearNoOperatingProfit("2025", 150, -10)));
        assertThat(EarningsQualityChecker.hasThreeYearUptrend(f)).isFalse();
    }

    @Test
    void partial_operating_profit_data_still_applies_strict_check() {
        // 일부 연도만 null이면(전부 null이 아니면) 원래대로 엄격 적용 — KR처럼 데이터 있는 경우 완화 우회 방지.
        AnnualFinancials f = new AnnualFinancials(List.of(
                year("2023", 100, 10, 8), yearNoOperatingProfit("2024", 120, 12), year("2025", 150, 20, 16)));
        assertThat(EarningsQualityChecker.hasThreeYearUptrend(f)).isFalse();
    }
}
