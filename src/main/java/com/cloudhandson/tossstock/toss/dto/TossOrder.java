package com.cloudhandson.tossstock.toss.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * POST/GET /api/v1/orders 응답(추정 스키마 — 실제 필드명 미검증).
 * 설계: docs/design/808-auto-trade-engine/fn-order-executor.md §4
 * 주의(전역 규칙): 문서 서술만 보고 만든 스키마다. dryRun=false 로 전환하기 전
 * 반드시 실제 응답으로 필드명을 재확인할 것 — 특히 orderId/status 필드명.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TossOrder(String orderId, String symbol, String side, String status,
                         String quantity, String price) {
}
