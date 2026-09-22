package com.cloudhandson.tossstock.autotrade;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 자동매매 보유 포지션(MyBatis 매핑). 설계: docs/design/808-auto-trade-engine/README.md §6 */
public class AutoTradePosition {
    private Long id;
    private String symbol;
    private String market;
    private String status; // HOLDING / EXITED
    private BigDecimal entryPrice;
    private BigDecimal entryQty;
    private LocalDateTime entryAt;
    private BigDecimal peakPrice;
    private BigDecimal budgetAllocated;
    private BigDecimal exitPrice;
    private String exitReason;
    private LocalDateTime exitAt;
    private boolean dryRun;
    private LocalDateTime createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getSymbol() { return symbol; }
    public void setSymbol(String symbol) { this.symbol = symbol; }
    public String getMarket() { return market; }
    public void setMarket(String market) { this.market = market; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public BigDecimal getEntryPrice() { return entryPrice; }
    public void setEntryPrice(BigDecimal entryPrice) { this.entryPrice = entryPrice; }
    public BigDecimal getEntryQty() { return entryQty; }
    public void setEntryQty(BigDecimal entryQty) { this.entryQty = entryQty; }
    public LocalDateTime getEntryAt() { return entryAt; }
    public void setEntryAt(LocalDateTime entryAt) { this.entryAt = entryAt; }
    public BigDecimal getPeakPrice() { return peakPrice; }
    public void setPeakPrice(BigDecimal peakPrice) { this.peakPrice = peakPrice; }
    public BigDecimal getBudgetAllocated() { return budgetAllocated; }
    public void setBudgetAllocated(BigDecimal budgetAllocated) { this.budgetAllocated = budgetAllocated; }
    public BigDecimal getExitPrice() { return exitPrice; }
    public void setExitPrice(BigDecimal exitPrice) { this.exitPrice = exitPrice; }
    public String getExitReason() { return exitReason; }
    public void setExitReason(String exitReason) { this.exitReason = exitReason; }
    public LocalDateTime getExitAt() { return exitAt; }
    public void setExitAt(LocalDateTime exitAt) { this.exitAt = exitAt; }
    public boolean isDryRun() { return dryRun; }
    public void setDryRun(boolean dryRun) { this.dryRun = dryRun; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
