package com.cloudhandson.tossstock.rangetrade;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.LocalDateTime;

/**
 * 보유종목 1건 + 현재가 → 가격손익 뷰(순수 함수, #848 — #808 {@code HoldingPnlView}와 동일
 * 원칙). 현재가를 모르면(null) 손익 필드도 null.
 */
public record RangeHoldingPnlView(String symbol, String name, String market, BigDecimal entryPrice,
                                   BigDecimal entryQty, BigDecimal currentPrice, BigDecimal unrealizedPnl,
                                   Double priceChangePct, boolean dryRun, LocalDateTime entryAt) {

    /** @param name 종목명(조회 실패/미보유 시 null — 화면에서 코드만 표시) */
    public static RangeHoldingPnlView of(RangeTradePosition p, BigDecimal currentPrice, String name) {
        BigDecimal unrealizedPnl = null;
        Double priceChangePct = null;
        if (currentPrice != null) {
            unrealizedPnl = currentPrice.subtract(p.getEntryPrice()).multiply(p.getEntryQty());
            priceChangePct = currentPrice.subtract(p.getEntryPrice())
                    .divide(p.getEntryPrice(), MathContext.DECIMAL64).doubleValue() * 100;
        }
        return new RangeHoldingPnlView(p.getSymbol(), name, p.getMarket(), p.getEntryPrice(), p.getEntryQty(),
                currentPrice, unrealizedPnl, priceChangePct, p.isDryRun(), p.getEntryAt());
    }
}
