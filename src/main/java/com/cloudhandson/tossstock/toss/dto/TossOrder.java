package com.cloudhandson.tossstock.toss.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * POST/GET /api/v1/orders 응답 — 2026-09-29 해성디에스(195870) 1주 매수/매도 실주문으로 실측 검증 완료.
 * 설계: docs/design/808-auto-trade-engine/fn-order-executor.md §4
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TossOrder(String orderId, String symbol, String side, String orderType,
                         String status, String price, String quantity, String orderAmount,
                         String currency, String orderedAt, String canceledAt, Execution execution) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Execution(String filledQuantity, String averageFilledPrice, String filledAmount,
                             String commission, String tax, String filledAt, String settlementDate) {
    }
}
