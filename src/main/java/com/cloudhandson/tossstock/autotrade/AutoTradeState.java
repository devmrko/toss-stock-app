package com.cloudhandson.tossstock.autotrade;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 자동매매 전역 상태(싱글턴, id=1). MyBatis 매핑. 설계: docs/design/808-auto-trade-engine/README.md §6 */
public class AutoTradeState {
    private Long id;
    private BigDecimal totalBudget;
    private BigDecimal perSymbolBudget;
    private boolean dryRun;
    private boolean circuitBreakerTripped;
    private LocalDateTime circuitBreakerTrippedAt;
    private LocalDateTime updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public BigDecimal getTotalBudget() { return totalBudget; }
    public void setTotalBudget(BigDecimal totalBudget) { this.totalBudget = totalBudget; }
    public BigDecimal getPerSymbolBudget() { return perSymbolBudget; }
    public void setPerSymbolBudget(BigDecimal perSymbolBudget) { this.perSymbolBudget = perSymbolBudget; }
    public boolean isDryRun() { return dryRun; }
    public void setDryRun(boolean dryRun) { this.dryRun = dryRun; }
    public boolean isCircuitBreakerTripped() { return circuitBreakerTripped; }
    public void setCircuitBreakerTripped(boolean circuitBreakerTripped) { this.circuitBreakerTripped = circuitBreakerTripped; }
    public LocalDateTime getCircuitBreakerTrippedAt() { return circuitBreakerTrippedAt; }
    public void setCircuitBreakerTrippedAt(LocalDateTime circuitBreakerTrippedAt) { this.circuitBreakerTrippedAt = circuitBreakerTrippedAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
