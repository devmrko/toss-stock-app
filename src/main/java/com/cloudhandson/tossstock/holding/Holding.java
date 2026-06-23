package com.cloudhandson.tossstock.holding;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 수동 입력 보유 1건. */
public class Holding {
    private Long id;
    private String symbol;
    private LocalDateTime buyAt;
    private BigDecimal buyPrice;
    private Long quantity;     // 수량(선택)
    private Double stopPct;    // 하드스탑 %(기본 8)
    private String memo;
    private LocalDateTime createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getSymbol() { return symbol; }
    public void setSymbol(String symbol) { this.symbol = symbol; }
    public LocalDateTime getBuyAt() { return buyAt; }
    public void setBuyAt(LocalDateTime buyAt) { this.buyAt = buyAt; }
    public BigDecimal getBuyPrice() { return buyPrice; }
    public void setBuyPrice(BigDecimal buyPrice) { this.buyPrice = buyPrice; }
    public Long getQuantity() { return quantity; }
    public void setQuantity(Long quantity) { this.quantity = quantity; }
    public Double getStopPct() { return stopPct; }
    public void setStopPct(Double stopPct) { this.stopPct = stopPct; }
    public String getMemo() { return memo; }
    public void setMemo(String memo) { this.memo = memo; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
