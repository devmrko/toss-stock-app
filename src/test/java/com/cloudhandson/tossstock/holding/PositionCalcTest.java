package com.cloudhandson.tossstock.holding;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** PositionCalc 평균단가 집계 검증. 설계: docs/design/433-trade-ledger/fn-position-calc.md */
class PositionCalcTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 6, 24, 12, 0);

    private static Holding t(String side, double price, Long qty, int daysAgo, Double stopPct) {
        Holding h = new Holding();
        h.setId((long) (Math.abs(daysAgo) + (int) price));
        h.setSymbol("005930");
        h.setSide(side);
        h.setBuyAt(NOW.minusDays(daysAgo));
        h.setBuyPrice(BigDecimal.valueOf(price));
        h.setQuantity(qty);
        h.setStopPct(stopPct);
        return h;
    }

    private static BigDecimal bd(double v) {
        return BigDecimal.valueOf(v);
    }

    private static PositionView calc(List<Holding> trades, BigDecimal current) {
        return PositionCalc.of("005930", "삼성전자", "반도체", trades, current, null, null, null, null, NOW);
    }

    @Test
    void broker_distinct_joined() {
        Holding b1 = t("BUY", 100, 10L, 6, 8.0); b1.setBroker("토스");
        Holding b2 = t("BUY", 200, 10L, 3, 8.0); b2.setBroker("키움");
        PositionView p = calc(List.of(b1, b2), bd(150));
        assertThat(p.broker()).isEqualTo("토스, 키움");
        assertThat(p.trades()).extracting(com.cloudhandson.tossstock.holding.TradeView::broker)
                .containsExactlyInAnyOrder("토스", "키움");
    }

    @Test
    void single_buy() {
        PositionView p = calc(List.of(t("BUY", 100, 10L, 5, 8.0)), bd(120));
        assertThat(p.netQty()).isEqualTo(10L);
        assertThat(p.avgCost()).isEqualByComparingTo(bd(100));
        assertThat(p.unrealizedPct()).isEqualTo(20.0);
        assertThat(p.unrealizedAmount()).isEqualTo(200L);
        assertThat(p.realizedAmount()).isNull();
    }

    @Test
    void weighted_average_two_buys() {
        PositionView p = calc(List.of(t("BUY", 100, 10L, 6, 8.0), t("BUY", 200, 10L, 3, 8.0)), bd(150));
        assertThat(p.netQty()).isEqualTo(20L);
        assertThat(p.avgCost()).isEqualByComparingTo(bd(150));
        assertThat(p.unrealizedPct()).isEqualTo(0.0);
    }

    @Test
    void partial_sell_keeps_avg_and_realizes() {
        PositionView p = calc(List.of(t("BUY", 100, 10L, 6, 8.0), t("SELL", 150, 4L, 2, null)), bd(120));
        assertThat(p.netQty()).isEqualTo(6L);
        assertThat(p.avgCost()).isEqualByComparingTo(bd(100));   // 평균단가법: 매도해도 평단 불변
        assertThat(p.realizedAmount()).isEqualTo(200L);          // 4×(150−100)
        assertThat(p.unrealizedAmount()).isEqualTo(120L);        // 6×(120−100)
    }

    @Test
    void full_sell_only_realized() {
        PositionView p = calc(List.of(t("BUY", 100, 10L, 6, 8.0), t("SELL", 150, 10L, 1, null)), bd(120));
        assertThat(p.netQty()).isEqualTo(0L);
        assertThat(p.realizedAmount()).isEqualTo(500L);          // 10×(150−100)
        assertThat(p.unrealizedAmount()).isNull();
    }

    @Test
    void fallback_when_buy_qty_null() {
        PositionView p = calc(List.of(t("BUY", 100, null, 6, 8.0), t("BUY", 200, null, 3, 8.0)), bd(180));
        assertThat(p.netQty()).isNull();
        assertThat(p.avgCost()).isEqualByComparingTo(bd(150));   // 단순평균
        assertThat(p.unrealizedAmount()).isNull();
        assertThat(p.unrealizedPct()).isEqualTo(20.0);           // (180−150)/150
    }

    @Test
    void stop_and_mdd() {
        PositionView p = PositionCalc.of("005930", "삼성전자", "반도체",
                List.of(t("BUY", 100, 10L, 5, 10.0)), bd(85), null, null, bd(130), bd(88), NOW);
        assertThat(p.stopPrice()).isEqualByComparingTo(bd(90));  // 100×(1−0.10)
        assertThat(p.belowStop()).isTrue();                      // 85 ≤ 90
        assertThat(p.stopHitSinceBuy()).isTrue();                // 저가 88 ≤ 90
        assertThat(p.ddFromPeak()).isEqualTo(34.62);             // (130−85)/130
    }
}
