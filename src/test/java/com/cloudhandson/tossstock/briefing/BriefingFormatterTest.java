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

    private static PositionView pos(Long netQty, double avg, double cur, double stopPct, double stopPrice,
                                    double unrealPct, Long unrealAmt, boolean belowStop) {
        return new PositionView("005930", "삼성전자", "반도체", netQty, bd(avg), bd(cur),
                null, null, unrealPct, unrealAmt, null, 5L, stopPct, bd(stopPrice), 7.0, belowStop,
                null, null, false, NOW.minusDays(5), List.of());
    }

    private static WatchlistQuote wq(String name, double last, Double rate) {
        return new WatchlistQuote(1L, "000660", name, null, bd(last), bd(last), bd(0), rate,
                100L, "KRW", 1, false);
    }

    @Test
    void holding_line_has_take_profit_and_stop() {
        PositionView p = pos(10L, 100000, 110000, 8.0, 92000, 10.0, 100000L, false);
        String out = BriefingFormatter.build("장 시작", NOW, List.of(p), List.of(), 20, 10);

        assertThat(out).contains("장 시작 브리핑");
        assertThat(out).contains("삼성전자").contains("10주").contains("+10.00%");
        assertThat(out).contains("🎯익절 120,000 (+20%)");   // 100000×1.2
        assertThat(out).contains("🛑손절 92,000 (-8%)");
    }

    @Test
    void below_stop_flagged() {
        String out = BriefingFormatter.build("장 마감", NOW, List.of(pos(10L, 100000, 80000, 8.0, 92000, -20.0, -200000L, true)),
                List.of(), 20, 10);
        assertThat(out).contains("⚠️손절이탈");
    }

    @Test
    void closed_position_excluded() {
        String out = BriefingFormatter.build("장 마감", NOW, List.of(pos(0L, 100000, 110000, 8.0, 92000, 10.0, null, false)),
                List.of(), 20, 10);
        assertThat(out).contains("보유 0종목").contains("• 없음");
    }

    @Test
    void watchlist_capped_to_limit() {
        List<WatchlistQuote> wl = List.of(wq("에이", 1000, 1.0), wq("비", 2000, -2.0), wq("씨", 3000, 0.5));
        String out = BriefingFormatter.build("장 시작", NOW, List.of(), wl, 20, 2);
        assertThat(out).contains("워치리스트 top 2");
        assertThat(out).contains("에이").contains("비");
        assertThat(out).doesNotContain("씨 ");
    }

    @Test
    void empty_everything() {
        String out = BriefingFormatter.build("장 시작", NOW, List.of(), List.of(), 20, 10);
        assertThat(out).contains("보유 0종목");
        assertThat(out).contains("📈 **워치리스트**");
    }
}
