package com.cloudhandson.tossstock.market;

import java.math.BigDecimal;
import java.time.LocalDate;

/** 일봉 OHLCV 한 행. */
public class DailyOhlcv {
    private String symbol;
    private LocalDate tradeDate;
    private BigDecimal openP;
    private BigDecimal highP;
    private BigDecimal lowP;
    private BigDecimal closeP;
    private Long volume;

    public DailyOhlcv() {
    }

    public DailyOhlcv(String symbol, LocalDate tradeDate, BigDecimal openP, BigDecimal highP,
                      BigDecimal lowP, BigDecimal closeP, Long volume) {
        this.symbol = symbol;
        this.tradeDate = tradeDate;
        this.openP = openP;
        this.highP = highP;
        this.lowP = lowP;
        this.closeP = closeP;
        this.volume = volume;
    }

    public String getSymbol() { return symbol; }
    public void setSymbol(String symbol) { this.symbol = symbol; }
    public LocalDate getTradeDate() { return tradeDate; }
    public void setTradeDate(LocalDate tradeDate) { this.tradeDate = tradeDate; }
    public BigDecimal getOpenP() { return openP; }
    public void setOpenP(BigDecimal openP) { this.openP = openP; }
    public BigDecimal getHighP() { return highP; }
    public void setHighP(BigDecimal highP) { this.highP = highP; }
    public BigDecimal getLowP() { return lowP; }
    public void setLowP(BigDecimal lowP) { this.lowP = lowP; }
    public BigDecimal getCloseP() { return closeP; }
    public void setCloseP(BigDecimal closeP) { this.closeP = closeP; }
    public Long getVolume() { return volume; }
    public void setVolume(Long volume) { this.volume = volume; }
}
