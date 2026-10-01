package com.cloudhandson.tossstock.rangetrade;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 레인지 트랙 전역 상태(싱글턴, id=1). #808 auto_trade_state 와 완전히 별개 테이블 —
 * 예산·슬롯·서킷브레이커가 모멘텀 트랙과 서로 영향을 주지 않는다(README §6, §11).
 * 설계: docs/design/818-range-trade-swing/README.md §6
 */
public class RangeTradeState {
    private Long id;
    private BigDecimal totalBudget;
    private Integer maxSymbols;
    private BigDecimal perSymbolBudget;
    private boolean dryRun;
    private boolean circuitBreakerTripped;
    private LocalDateTime circuitBreakerTrippedAt;
    private LocalDateTime updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public BigDecimal getTotalBudget() { return totalBudget; }
    public void setTotalBudget(BigDecimal totalBudget) { this.totalBudget = totalBudget; }
    public Integer getMaxSymbols() { return maxSymbols; }
    public void setMaxSymbols(Integer maxSymbols) { this.maxSymbols = maxSymbols; }
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
