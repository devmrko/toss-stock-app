package com.cloudhandson.tossstock.autotrade;

import java.time.LocalDateTime;

/** 수동 큐레이션 매수 후보(MyBatis 매핑). 설계: docs/design/808-auto-trade-engine/README.md §6 */
public class AutoTradeCandidate {
    private Long id;
    private String symbol;
    private String market;
    private String valuationNote;
    private LocalDateTime valuationCheckedAt;
    private boolean active;
    private LocalDateTime createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getSymbol() { return symbol; }
    public void setSymbol(String symbol) { this.symbol = symbol; }
    public String getMarket() { return market; }
    public void setMarket(String market) { this.market = market; }
    public String getValuationNote() { return valuationNote; }
    public void setValuationNote(String valuationNote) { this.valuationNote = valuationNote; }
    public LocalDateTime getValuationCheckedAt() { return valuationCheckedAt; }
    public void setValuationCheckedAt(LocalDateTime valuationCheckedAt) { this.valuationCheckedAt = valuationCheckedAt; }
    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
