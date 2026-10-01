package com.cloudhandson.tossstock.market;

import java.math.BigDecimal;

/**
 * 한 종목의 최근 N거래일 윈도우 통계(배치 조회 결과) — 유니버스 전체를 쿼리 1번으로 1차 스크리닝할 때 쓴다.
 * 최종 판정은 항상 실제 일봉으로 RangeBoundChecker/LiquidityChecker 가 한다(#818 README §5).
 * 설계: docs/design/818-range-trade-swing/README.md §5, §7(rangeStatsBatch)
 */
public class DailyRangeStats {
    private String symbol;
    private int barCount;
    private BigDecimal maxHigh;
    private BigDecimal minLow;
    private BigDecimal lastClose;
    /** 윈도우 전반부(과거쪽) 종가 평균 — 홀수개면 중간 1개가 전반부에 포함. */
    private BigDecimal firstHalfAvgClose;
    /** 윈도우 후반부(최근쪽) 종가 평균. */
    private BigDecimal secondHalfAvgClose;

    public String getSymbol() { return symbol; }
    public void setSymbol(String symbol) { this.symbol = symbol; }
    public int getBarCount() { return barCount; }
    public void setBarCount(int barCount) { this.barCount = barCount; }
    public BigDecimal getMaxHigh() { return maxHigh; }
    public void setMaxHigh(BigDecimal maxHigh) { this.maxHigh = maxHigh; }
    public BigDecimal getMinLow() { return minLow; }
    public void setMinLow(BigDecimal minLow) { this.minLow = minLow; }
    public BigDecimal getLastClose() { return lastClose; }
    public void setLastClose(BigDecimal lastClose) { this.lastClose = lastClose; }
    public BigDecimal getFirstHalfAvgClose() { return firstHalfAvgClose; }
    public void setFirstHalfAvgClose(BigDecimal firstHalfAvgClose) { this.firstHalfAvgClose = firstHalfAvgClose; }
    public BigDecimal getSecondHalfAvgClose() { return secondHalfAvgClose; }
    public void setSecondHalfAvgClose(BigDecimal secondHalfAvgClose) { this.secondHalfAvgClose = secondHalfAvgClose; }
}
