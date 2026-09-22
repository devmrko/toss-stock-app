package com.cloudhandson.tossstock.toss.dto;

/**
 * POST /api/v1/orders 요청 바디(추정 스키마 — 실제 필드명 미검증).
 * 설계: docs/design/808-auto-trade-engine/fn-order-executor.md §4
 * 주의(전역 규칙): dryRun=false 전환 전 실제 호출로 필드명 재확인 필수.
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
