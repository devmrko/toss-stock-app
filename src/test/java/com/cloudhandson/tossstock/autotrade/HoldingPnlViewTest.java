package com.cloudhandson.tossstock.autotrade;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class HoldingPnlViewTest {

    private static AutoTradePosition position(BigDecimal entryPrice, BigDecimal entryQty) {
        AutoTradePosition p = new AutoTradePosition();
        p.setSymbol("005930");
        p.setMarket("KR");
        p.setEntryPrice(entryPrice);
        p.setEntryQty(entryQty);
        p.setEntryAt(LocalDateTime.of(2026, 10, 7, 9, 0));
        p.setDryRun(false);
        return p;
    }

    @Test
    void 현재가_있으면_손익과_변동률_계산() {
        HoldingPnlView view = HoldingPnlView.of(position(BigDecimal.valueOf(10000), BigDecimal.valueOf(10)),
                BigDecimal.valueOf(11000));

        assertThat(view.unrealizedPnl()).isEqualTo(BigDecimal.valueOf(10000));
        assertThat(view.priceChangePct()).isEqualTo(10.0);
    }

    @Test
    void 현재가_없으면_손익필드도_null_0으로_단정하지_않음() {
        HoldingPnlView view = HoldingPnlView.of(position(BigDecimal.valueOf(10000), BigDecimal.valueOf(10)), null);

        assertThat(view.currentPrice()).isNull();
        assertThat(view.unrealizedPnl()).isNull();
        assertThat(view.priceChangePct()).isNull();
    }

    @Test
    void 현재가가_매수가와_같으면_변동률_0() {
        HoldingPnlView view = HoldingPnlView.of(position(BigDecimal.valueOf(10000), BigDecimal.valueOf(10)),
                BigDecimal.valueOf(10000));

        assertThat(view.unrealizedPnl()).isEqualTo(BigDecimal.ZERO);
        assertThat(view.priceChangePct()).isEqualTo(0.0);
    }
}
