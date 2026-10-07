package com.cloudhandson.tossstock.autotrade;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class TradingFeeCalculatorTest {

    @Test
    void kr_매수는_수수료만_세금은_0() {
        BigDecimal filledAmount = new BigDecimal("1000000");
        assertThat(TradingFeeCalculator.commission(filledAmount, "KR")).isEqualTo(new BigDecimal("150"));
        assertThat(TradingFeeCalculator.tax(filledAmount, "KR", "BUY")).isEqualTo(BigDecimal.ZERO);
    }

    @Test
    void kr_매도는_수수료와_세금_둘다() {
        BigDecimal filledAmount = new BigDecimal("1000000");
        assertThat(TradingFeeCalculator.commission(filledAmount, "KR")).isEqualTo(new BigDecimal("150"));
        assertThat(TradingFeeCalculator.tax(filledAmount, "KR", "SELL")).isEqualTo(new BigDecimal("2000"));
    }

    @Test
    void us_매수는_수수료만_세금은_0() {
        BigDecimal filledAmount = new BigDecimal("1000000");
        assertThat(TradingFeeCalculator.commission(filledAmount, "US")).isEqualTo(new BigDecimal("1000"));
        assertThat(TradingFeeCalculator.tax(filledAmount, "US", "BUY")).isEqualTo(BigDecimal.ZERO);
    }

    @Test
    void us_매도도_세금은_0_미확인이라() {
        BigDecimal filledAmount = new BigDecimal("1000000");
        assertThat(TradingFeeCalculator.commission(filledAmount, "US")).isEqualTo(new BigDecimal("1000"));
        assertThat(TradingFeeCalculator.tax(filledAmount, "US", "SELL")).isEqualTo(BigDecimal.ZERO);
    }

    @Test
    void 체결금액_0이거나_null이면_전부_0() {
        assertThat(TradingFeeCalculator.commission(BigDecimal.ZERO, "KR")).isEqualTo(BigDecimal.ZERO);
        assertThat(TradingFeeCalculator.commission(null, "KR")).isEqualTo(BigDecimal.ZERO);
        assertThat(TradingFeeCalculator.tax(BigDecimal.ZERO, "KR", "SELL")).isEqualTo(BigDecimal.ZERO);
        assertThat(TradingFeeCalculator.tax(null, "KR", "SELL")).isEqualTo(BigDecimal.ZERO);
    }
}
