package com.cloudhandson.tossstock.rangetrade;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 레인지 트랙 신호/주문 감사 로그(MyBatis 매핑).
 * 설계: docs/design/818-range-trade-swing/README.md §6
 */
public class RangeTradeOrderLog {
    private Long id;
    private String symbol;
    private String side; // BUY / SELL
    private String reason;
    private boolean dryRun;
    private BigDecimal requestedQty;
    private BigDecimal requestedPrice;
    private String tossOrderId;
    private boolean success;
    private String message;
    private BigDecimal commission;
    private BigDecimal tax;
    private BigDecimal entryPrice;
    private LocalDateTime createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getSymbol() { return symbol; }
    public void setSymbol(String symbol) { this.symbol = symbol; }
    public String getSide() { return side; }
    public void setSide(String side) { this.side = side; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public boolean isDryRun() { return dryRun; }
    public void setDryRun(boolean dryRun) { this.dryRun = dryRun; }
    public BigDecimal getRequestedQty() { return requestedQty; }
    public void setRequestedQty(BigDecimal requestedQty) { this.requestedQty = requestedQty; }
    public BigDecimal getRequestedPrice() { return requestedPrice; }
    public void setRequestedPrice(BigDecimal requestedPrice) { this.requestedPrice = requestedPrice; }
    public String getTossOrderId() { return tossOrderId; }
    public void setTossOrderId(String tossOrderId) { this.tossOrderId = tossOrderId; }
    public boolean isSuccess() { return success; }
    public void setSuccess(boolean success) { this.success = success; }
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
    public BigDecimal getCommission() { return commission; }
    public void setCommission(BigDecimal commission) { this.commission = commission; }
    public BigDecimal getTax() { return tax; }
    public void setTax(BigDecimal tax) { this.tax = tax; }
    public BigDecimal getEntryPrice() { return entryPrice; }
    public void setEntryPrice(BigDecimal entryPrice) { this.entryPrice = entryPrice; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
