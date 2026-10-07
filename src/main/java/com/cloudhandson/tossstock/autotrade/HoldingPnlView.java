package com.cloudhandson.tossstock.autotrade;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.LocalDateTime;

/**
 * 보유종목 1건 + 현재가 → 가격손익 뷰(순수 함수, #848). 현재가를 모르면(null) 손익 필드도
 * null — 시세조회 실패를 손익 0으로 단정하지 않는다.
 */
public record HoldingPnlView(String symbol, String market, BigDecimal entryPrice, BigDecimal entryQty,
                              BigDecimal currentPrice, BigDecimal unrealizedPnl, Double priceChangePct,
                              boolean dryRun, LocalDateTime entryAt) {

    public static HoldingPnlView of(AutoTradePosition p, BigDecimal currentPrice) {
        BigDecimal unrealizedPnl = null;
        Double priceChangePct = null;
        if (currentPrice != null) {
            unrealizedPnl = currentPrice.subtract(p.getEntryPrice()).multiply(p.getEntryQty());
            priceChangePct = currentPrice.subtract(p.getEntryPrice())
                    .divide(p.getEntryPrice(), MathContext.DECIMAL64).doubleValue() * 100;
        }
        return new HoldingPnlView(p.getSymbol(), p.getMarket(), p.getEntryPrice(), p.getEntryQty(),
                currentPrice, unrealizedPnl, priceChangePct, p.isDryRun(), p.getEntryAt());
    }
}
