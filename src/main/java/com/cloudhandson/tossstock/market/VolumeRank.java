package com.cloudhandson.tossstock.market;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 거래량 순위 행 (저장 + 응답 겸용). */
public class VolumeRank {
    private LocalDateTime asOf;
    private int rnk;
    private String symbol;
    private String name;
    private String market;
    private Long volume;
    private BigDecimal lastPrice;
    private BigDecimal prevClose;
    private Double changeRate;

    public LocalDateTime getAsOf() { return asOf; }
    public void setAsOf(LocalDateTime asOf) { this.asOf = asOf; }
    public int getRnk() { return rnk; }
    public void setRnk(int rnk) { this.rnk = rnk; }
    public String getSymbol() { return symbol; }
    public void setSymbol(String symbol) { this.symbol = symbol; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getMarket() { return market; }
    public void setMarket(String market) { this.market = market; }
    public Long getVolume() { return volume; }
    public void setVolume(Long volume) { this.volume = volume; }
    public BigDecimal getLastPrice() { return lastPrice; }
    public void setLastPrice(BigDecimal lastPrice) { this.lastPrice = lastPrice; }
    public BigDecimal getPrevClose() { return prevClose; }
    public void setPrevClose(BigDecimal prevClose) { this.prevClose = prevClose; }
    public Double getChangeRate() { return changeRate; }
    public void setChangeRate(Double changeRate) { this.changeRate = changeRate; }
}
