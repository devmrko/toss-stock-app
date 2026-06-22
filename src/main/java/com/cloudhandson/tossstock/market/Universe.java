package com.cloudhandson.tossstock.market;

/** KRX 상장 종목(유니버스). */
public class Universe {
    private String symbol;
    private String name;
    private String market;

    public Universe() {
    }

    public Universe(String symbol, String name, String market) {
        this.symbol = symbol;
        this.name = name;
        this.market = market;
    }

    public String getSymbol() { return symbol; }
    public void setSymbol(String symbol) { this.symbol = symbol; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getMarket() { return market; }
    public void setMarket(String market) { this.market = market; }
}
