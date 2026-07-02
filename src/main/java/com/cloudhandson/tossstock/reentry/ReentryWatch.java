package com.cloudhandson.tossstock.reentry;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 재진입 감시 대상(MyBatis 매핑). 설계: docs/design/reentry-alert/README.md §6 */
public class ReentryWatch {
    private Long id;
    private String symbol;
    private BigDecimal referencePrice;
    private Double stage1Pct;
    private Double stage2Pct;
    private LocalDateTime stage1AlertedAt;
    private LocalDateTime stage2AlertedAt;
    private boolean active;
    private LocalDateTime createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getSymbol() { return symbol; }
    public void setSymbol(String symbol) { this.symbol = symbol; }

    public BigDecimal getReferencePrice() { return referencePrice; }
    public void setReferencePrice(BigDecimal referencePrice) { this.referencePrice = referencePrice; }

    public Double getStage1Pct() { return stage1Pct; }
    public void setStage1Pct(Double stage1Pct) { this.stage1Pct = stage1Pct; }

    public Double getStage2Pct() { return stage2Pct; }
    public void setStage2Pct(Double stage2Pct) { this.stage2Pct = stage2Pct; }

    public LocalDateTime getStage1AlertedAt() { return stage1AlertedAt; }
    public void setStage1AlertedAt(LocalDateTime stage1AlertedAt) { this.stage1AlertedAt = stage1AlertedAt; }

    public LocalDateTime getStage2AlertedAt() { return stage2AlertedAt; }
    public void setStage2AlertedAt(LocalDateTime stage2AlertedAt) { this.stage2AlertedAt = stage2AlertedAt; }

    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
