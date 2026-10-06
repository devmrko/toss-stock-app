package com.cloudhandson.tossstock.autotrade;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 매수 결정근거 문자열 검증 — "나중에 왜 샀는지 파악"이 목적이므로 핵심 수치가 빠지지 않는지 본다.
 * 설계: docs/design/832-decision-rationale-logging/README.md §10
 */
class BuyRationaleTest {

    private static final FundamentalScore SCORE_3 =
            new FundamentalScore(false, true, true, true, false); // 실적X 재무O 배당O 유동O 강도X

    @Test
    void cheap_path_contains_per_pbr_and_checklist_and_popularity_and_news() {
        String s = BuyRationale.describe(new Valuation(BigDecimal.valueOf(11.6), BigDecimal.valueOf(0.47)),
                true, false, null, SCORE_3, false, 0, true, 2.34, "S5 \"안랩, 보안주 급등\"");

        assertThat(s).isEqualTo("PER11.6/PBR0.47(저평가) 펀더3/5(실적X재무O배당O유동O강도X) "
                + "인기:가격+2.34% 뉴스:S5 \"안랩, 보안주 급등\"");
    }

    @Test
    void volume_spike_path_reports_ratio_not_price_move() {
        String s = BuyRationale.describe(new Valuation(BigDecimal.valueOf(8), BigDecimal.valueOf(0.8)),
                true, false, null, SCORE_3, true, 5.237, false, 0.4, "S4 \"수주 공시\"");

        assertThat(s).contains("인기:거래량x5.24배").doesNotContain("가격");
    }

    @Test
    void both_triggers_are_reported_together() {
        String s = BuyRationale.describe(new Valuation(BigDecimal.ONE, BigDecimal.ONE),
                true, false, null, SCORE_3, true, 3.0, true, 2.5, "S4 \"뉴스\"");

        assertThat(s).contains("인기:거래량x3배,가격+2.5%");
    }

    @Test
    void rerate_catalyst_path_marks_catalyst_and_headline() {
        String s = BuyRationale.describe(new Valuation(BigDecimal.valueOf(25.1), BigDecimal.valueOf(3.2)),
                false, true, "S5 \"자사주 소각 결정\"", SCORE_3, true, 3.1, false, 0, "S5 \"자사주 소각 결정\"");

        assertThat(s).contains("PER25.1/PBR3.2(재평가촉매:\"S5 \"자사주 소각 결정\"\")");
    }

    @Test
    void cheap_and_catalyst_both_listed() {
        String s = BuyRationale.describe(new Valuation(BigDecimal.TEN, BigDecimal.ONE),
                true, true, null, SCORE_3, true, 3.1, false, 0, "S4 \"뉴스\"");

        assertThat(s).contains("(저평가,재평가촉매)");
    }

    @Test
    void null_inputs_render_as_na_without_throwing() {
        String s = BuyRationale.describe(null, false, false, null, null, false, 0, false, 0, null);

        assertThat(s).isEqualTo("PERN/A/PBRN/A(통과경로N/A) 펀더N/A 인기:N/A 뉴스:N/A");
    }

    @Test
    void missing_per_pbr_fields_render_as_na() {
        String s = BuyRationale.describe(new Valuation(null, BigDecimal.valueOf(0.5)),
                false, true, null, SCORE_3, false, 0, true, 3.0, "S4 \"뉴스\"");

        assertThat(s).startsWith("PERN/A/PBR0.5(재평가촉매)");
    }

    @Test
    void long_news_title_is_clipped_and_newlines_flattened() {
        String longTitle = "S5 \"" + "가".repeat(200) + "\"";
        String s = BuyRationale.describe(new Valuation(BigDecimal.TEN, BigDecimal.ONE),
                true, false, null, SCORE_3, false, 0, true, 2.0, "줄바꿈\n포함 " + longTitle);

        assertThat(s).doesNotContain("\n");
        assertThat(s.length()).isLessThan(200); // message VARCHAR2(500) 여유 확보
    }

    @Test
    void all_five_checklist_items_passing_shows_5_of_5() {
        String s = BuyRationale.describe(new Valuation(BigDecimal.TEN, BigDecimal.ONE), true, false, null,
                new FundamentalScore(true, true, true, true, true), false, 0, true, 2.0, null);

        assertThat(s).contains("펀더5/5(실적O재무O배당O유동O강도O)");
    }
}
