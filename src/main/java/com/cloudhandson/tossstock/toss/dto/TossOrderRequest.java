package com.cloudhandson.tossstock.toss.dto;

/**
 * POST /api/v1/orders 요청 바디 — 2026-09-29 해성디에스(195870) 1주 매수/매도 실주문으로 실측 검증 완료.
 * 설계: docs/design/808-auto-trade-engine/fn-order-executor.md §4
 */
public record TossOrderRequest(String symbol, String side, String orderType,
                                String quantity, String price, String currency) {

    public static TossOrderRequest marketBuy(String symbol, String quantity, String currency) {
        return new TossOrderRequest(symbol, "BUY", "MARKET", quantity, null, currency);
    }

    public static TossOrderRequest marketSell(String symbol, String quantity, String currency) {
        return new TossOrderRequest(symbol, "SELL", "MARKET", quantity, null, currency);
    }
}
