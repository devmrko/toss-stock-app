package com.cloudhandson.tossstock.holding;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class HoldingCalcTest {

    private static Holding h(double buy, double stopPct, Long qty, LocalDateTime buyAt) {
        Holding x = new Holding();
        x.setId(1L);
        x.setSymbol("005930");
        x.setBuyAt(buyAt);
        x.setBuyPrice(BigDecimal.valueOf(buy));
        x.setQuantity(qty);
        x.setStopPct(stopPct);
        return x;
    }

    private static BigDecimal bd(double v) {
        return BigDecimal.valueOf(v);
    }

    @Test
    void profit_case_full_metrics() {
        LocalDateTime now = LocalDateTime.of(2026, 6, 23, 12, 0);
        LocalDateTime buyAt = now.minusDays(5);
        HoldingView v = HoldingCalc.of(h(10000, 8, 10L, buyAt), "삼성전자", "반도체",
                bd(12000), bd(13000), bd(9500), now);

        assertThat(v.stopPrice()).isEqualByComparingTo("9200.00");   // 10000*(1-0.08)
        assertThat(v.returnPct()).isEqualTo(20.0);                    // +20%
        assertThat(v.returnAmount()).isEqualTo(20000L);              // (12000-10000)*10
        assertThat(v.stopDistPct()).isEqualTo(23.33);                // (12000-9200)/12000
        assertThat(v.belowStop()).isFalse();
        assertThat(v.daysHeld()).isEqualTo(5L);
        assertThat(v.ddFromPeak()).isEqualTo(7.69);                  // (13000-12000)/13000
        assertThat(v.stopHitSinceBuy()).isFalse();                   // 9500 > 9200
    }

    @Test
    void below_stop_flags_and_negative_distance() {
        LocalDateTime now = LocalDateTime.now();
        HoldingView v = HoldingCalc.of(h(10000, 8, null, now.minusDays(1)), null, null,
                bd(9000), bd(11000), bd(9000), now);

        assertThat(v.belowStop()).isTrue();                          // 9000 <= 9200
        assertThat(v.stopDistPct()).isNegative();                    // 스탑 아래
        assertThat(v.stopHitSinceBuy()).isTrue();                    // trough 9000 <= 9200
        assertThat(v.returnAmount()).isNull();                       // 수량 없음
        assertThat(v.returnPct()).isEqualTo(-10.0);
    }

    @Test
    void null_current_keeps_stop_price() {
        LocalDateTime now = LocalDateTime.now();
        HoldingView v = HoldingCalc.of(h(10000, 8, 10L, now), "x", null, null, null, null, now);
        assertThat(v.currentPrice()).isNull();
        assertThat(v.returnPct()).isNull();
        assertThat(v.stopDistPct()).isNull();
        assertThat(v.stopPrice()).isEqualByComparingTo("9200.00");   // 매수가 기반은 항상
        assertThat(v.daysHeld()).isEqualTo(0L);
    }
}
