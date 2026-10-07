package com.cloudhandson.tossstock.rangetrade;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class RangeHoldingPnlViewTest {

    private static RangeTradePosition position(BigDecimal entryPrice, BigDecimal entryQty) {
        RangeTradePosition p = new RangeTradePosition();
        p.setSymbol("001540");
        p.setMarket("KR");
        p.setEntryPrice(entryPrice);
        p.setEntryQty(entryQty);
        p.setEntryAt(LocalDateTime.of(2026, 10, 2, 16, 26));
        p.setDryRun(true);
        return p;
    }

    @Test
    void 현재가_있으면_손익과_변동률_계산() {
        RangeHoldingPnlView view = RangeHoldingPnlView.of(position(BigDecimal.valueOf(10000), BigDecimal.valueOf(50)),
                BigDecimal.valueOf(9800));

        assertThat(view.unrealizedPnl()).isEqualTo(BigDecimal.valueOf(-10000));
        assertThat(view.priceChangePct()).isEqualTo(-2.0);
    }

    @Test
    void 현재가_없으면_손익필드도_null_0으로_단정하지_않음() {
        RangeHoldingPnlView view = RangeHoldingPnlView.of(position(BigDecimal.valueOf(10000), BigDecimal.valueOf(50)),
                null);

        assertThat(view.currentPrice()).isNull();
        assertThat(view.unrealizedPnl()).isNull();
        assertThat(view.priceChangePct()).isNull();
    }
}
