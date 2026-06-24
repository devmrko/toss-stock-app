package com.cloudhandson.tossstock.briefing;

import com.cloudhandson.tossstock.holding.PositionView;
import com.cloudhandson.tossstock.watchlist.WatchlistQuote;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class BriefingFormatterTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 6, 25, 9, 0);

    private static BigDecimal bd(double v) {
        return BigDecimal.valueOf(v);
    }

    /** avg/cur/peak/stopPct + netQty/unrealized 로 포지션 구성. */
    private static PositionView pos(Long netQty, double avg, double cur, double peak, double stopPct,
                                    double unrealPct, Long unrealAmt) {
        return new PositionView("005930", "삼성전자", "반도체", netQty, bd(avg), bd(cur),
                null, null, unrealPct, unrealAmt, null, 5L, stopPct, bd(avg * (1 - stopPct / 100)), 7.0, false,
                bd(peak), null, false, NOW.minusDays(5), null, List.of());
    }

    private static WatchlistQuote wq(String name, double last, Double rate) {
        return new WatchlistQuote(1L, "000660", name, null, bd(last), bd(last), bd(0), rate,
                100L, "KRW", 1, false);
    }

    private static String build(List<PositionView> p, List<HoldingNews> news, List<WatchlistQuote> w, int lim) {
        return BriefingFormatter.build("장 시작", NOW, p, news, w, lim);
    }

    @Test
    void adjusted_stop_uses_peak_no_take_profit() {
        // 평단 100,000 · 고점 130,000 · 손절 8% → 조정손절 = 130,000×0.92 = 119,600
        String out = build(List.of(pos(10L, 100000, 125000, 130000, 8.0, 25.0, 250000L)), List.of(), List.of(), 10);
        assertThat(out).contains("조정 손절가 119,600").contains("고점 기준 -8%");
        assertThat(out).doesNotContain("익절");
    }

    @Test
    void breached_when_current_below_adjusted_stop() {
        // 현재 110,000 ≤ 조정손절 119,600 → ⚠️이탈 (베이스 손절 92,000 보다는 위)
        String out = build(List.of(pos(10L, 100000, 110000, 130000, 8.0, 10.0, 100000L)), List.of(), List.of(), 10);
        assertThat(out).contains("⚠️이탈");
    }

    @Test
    void holding_news_section() {
        String out = build(List.of(pos(10L, 100000, 125000, 130000, 8.0, 25.0, 250000L)),
                List.of(new HoldingNews("삼성전자", "S1", "삼성전자 어닝 쇼크 우려")), List.of(), 10);
        assertThat(out).contains("📰 **보유 관련 뉴스**");
        assertThat(out).contains("🔴 S1").contains("삼성전자 어닝 쇼크 우려");
    }

    @Test
    void closed_position_excluded() {
        String out = build(List.of(pos(0L, 100000, 110000, 130000, 8.0, 10.0, null)), List.of(), List.of(), 10);
        assertThat(out).contains("보유 0종목").contains("• 없음");
    }

    @Test
    void watchlist_capped_to_limit() {
        List<WatchlistQuote> wl = List.of(wq("에이", 1000, 1.0), wq("비", 2000, -2.0), wq("씨", 3000, 0.5));
        String out = build(List.of(), List.of(), wl, 2);
        assertThat(out).contains("워치리스트 top 2").contains("에이").contains("비");
        assertThat(out).doesNotContain("씨 ");
    }

    @Test
    void empty_everything() {
        String out = build(List.of(), List.of(), List.of(), 10);
        assertThat(out).contains("보유 0종목");
        assertThat(out).contains("📈 **워치리스트**");
    }
}
