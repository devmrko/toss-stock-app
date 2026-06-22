package com.cloudhandson.tossstock.watchlist;

import java.time.LocalDateTime;

/** 관심종목(스모크/헬스용 MyBatis 매핑 대상). */
public class Watchlist {
    private Long id;
    private String symbol;
    private String memo;
    private LocalDateTime createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getSymbol() { return symbol; }
    public void setSymbol(String symbol) { this.symbol = symbol; }

    public String getMemo() { return memo; }
    public void setMemo(String memo) { this.memo = memo; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
