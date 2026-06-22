package com.cloudhandson.tossstock.market;

/** KRX 상장 종목(유니버스). */
public class Universe {
    private String symbol;
    private String name;
    private String market;
    private String ksic;     // KRX 업종(KSIC)
    private String product;  // 주요제품

    public Universe() {
    }

    public Universe(String symbol, String name, String market, String ksic, String product) {
        this.symbol = symbol;
        this.name = name;
        this.market = market;
        this.ksic = ksic;
        this.product = product;
    }

    public String getSymbol() { return symbol; }
    public void setSymbol(String symbol) { this.symbol = symbol; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getMarket() { return market; }
    public void setMarket(String market) { this.market = market; }
    public String getKsic() { return ksic; }
    public void setKsic(String ksic) { this.ksic = ksic; }
    public String getProduct() { return product; }
    public void setProduct(String product) { this.product = product; }
}
