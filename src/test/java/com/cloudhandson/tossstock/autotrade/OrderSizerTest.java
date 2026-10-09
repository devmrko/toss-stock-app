package com.cloudhandson.tossstock.autotrade;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #886 주문 수량 계산. 설계: docs/design/886-us-fx-order-sizing/fn-buyQuantity.md §10
 */
class OrderSizerTest {

    private static final BigDecimal BUDGET = BigDecimal.valueOf(600_000);
    private static final BigDecimal FX = new BigDecimal("1340.78");
    private static final BigDecimal CASH_1500 = new BigDecimal("1500");

    private static BigDecimal usd(String s) {
        return new BigDecimal(s);
    }

    // ---- KR 회귀 ----

    @Test
    void kr은_원화예산을_주가로_나눈다() {
        // AC6 — 실제 포지션 재현: 제일기획 18,940원에 31주.
        OrderSizer.Sizing s = OrderSizer.buyQuantity("KR", BUDGET, BigDecimal.valueOf(18_940),
                null, null);
        assertThat(s.qty()).isEqualByComparingTo("31");
        assertThat(s.note()).contains("예산 600000원").contains("31주");
    }

    @Test
    void kr은_환율과_예수금을_무시한다() {
        OrderSizer.Sizing a = OrderSizer.buyQuantity("KR", BUDGET, BigDecimal.valueOf(18_940), null, null);
        OrderSizer.Sizing b = OrderSizer.buyQuantity("KR", BUDGET, BigDecimal.valueOf(18_940), FX, CASH_1500);
        assertThat(a.qty()).isEqualByComparingTo(b.qty());
    }

    // ---- US 환산 ----

    @Test
    void us는_환율로_환산한_예산을_쓴다() {
        // AC1 — 600,000 / 1340.78 = $447.50. 결함 코드는 600,000 / 456.35 = 1,315주였다.
        OrderSizer.Sizing s = OrderSizer.buyQuantity("US", BUDGET, usd("456.35"), FX, CASH_1500);
        assertThat(s.qty()).isEqualByComparingTo("0");   // $447.50 < $456.35 → 1주도 못 산다
        assertThat(s.note()).contains("환율 1340.78").contains("$447.50").contains("상한=예산");
    }

    @Test
    void us_저가주는_환산예산_안에서_수량이_나온다() {
        // SMCI $41.29 → $447.50 / 41.29 = 10주
        assertThat(OrderSizer.buyQuantity("US", BUDGET, usd("41.29"), FX, CASH_1500).qty())
                .isEqualByComparingTo("10");
        // LH $317.02 → 1주 (예산 소진율 71%)
        assertThat(OrderSizer.buyQuantity("US", BUDGET, usd("317.02"), FX, CASH_1500).qty())
                .isEqualByComparingTo("1");
    }

    @Test
    void us_예수금이_작으면_예수금이_상한이다() {
        // AC4 — 예수금 $100 < 환산예산 $447.50 → $100 / $41.29 = 2주
        OrderSizer.Sizing s = OrderSizer.buyQuantity("US", BUDGET, usd("41.29"), FX, usd("100"));
        assertThat(s.qty()).isEqualByComparingTo("2");
        assertThat(s.note()).contains("상한=예수금");
    }

    @Test
    void us_예수금이_충분하면_예산이_상한이다() {
        OrderSizer.Sizing s = OrderSizer.buyQuantity("US", BUDGET, usd("41.29"), FX, usd("100000"));
        assertThat(s.qty()).isEqualByComparingTo("10");
        assertThat(s.note()).contains("상한=예산");
    }

    // ---- fail-closed ----

    @Test
    void us_환율이_없으면_매수하지_않는다() {
        // AC2
        assertThat(OrderSizer.buyQuantity("US", BUDGET, usd("41.29"), null, CASH_1500).qty())
                .isEqualByComparingTo("0");
        assertThat(OrderSizer.buyQuantity("US", BUDGET, usd("41.29"), null, CASH_1500).note())
                .contains("환율 조회 실패");
        assertThat(OrderSizer.buyQuantity("US", BUDGET, usd("41.29"), BigDecimal.ZERO, CASH_1500).qty())
                .isEqualByComparingTo("0");
    }

    @Test
    void us_예수금_조회가_실패하면_매수하지_않는다() {
        // AC3
        OrderSizer.Sizing s = OrderSizer.buyQuantity("US", BUDGET, usd("41.29"), FX, null);
        assertThat(s.qty()).isEqualByComparingTo("0");
        assertThat(s.note()).contains("USD 예수금 조회 실패");
    }

    @Test
    void us_예수금이_0이면_수량이_0이다() {
        // AC5 — 조회는 됐고 돈이 없는 경우. "조회 실패"와 구분된다.
        OrderSizer.Sizing s = OrderSizer.buyQuantity("US", BUDGET, usd("41.29"), FX, BigDecimal.ZERO);
        assertThat(s.qty()).isEqualByComparingTo("0");
        assertThat(s.note()).contains("상한=예수금").doesNotContain("조회 실패");
    }

    @Test
    void 현재가가_없으면_수량이_0이다() {
        assertThat(OrderSizer.buyQuantity("US", BUDGET, null, FX, CASH_1500).note())
                .contains("현재가 없음");
        assertThat(OrderSizer.buyQuantity("KR", BUDGET, BigDecimal.ZERO, null, null).qty())
                .isEqualByComparingTo("0");
    }

    @Test
    void 예산이_없으면_수량이_0이다() {
        assertThat(OrderSizer.buyQuantity("KR", null, BigDecimal.valueOf(100), null, null).note())
                .contains("슬롯예산 없음");
        assertThat(OrderSizer.buyQuantity("US", BigDecimal.ZERO, usd("41.29"), FX, CASH_1500).qty())
                .isEqualByComparingTo("0");
    }

    @Test
    void 환율이_비정상이어도_예수금_상한이_과대주문을_막는다() {
        // 환율 10 → 환산예산 $60,000 이지만 예수금 $1,500 이 상한이 된다.
        OrderSizer.Sizing s = OrderSizer.buyQuantity("US", BUDGET, usd("41.29"),
                BigDecimal.TEN, CASH_1500);
        assertThat(s.qty()).isEqualByComparingTo("36");   // 1500 / 41.29
        assertThat(s.note()).contains("상한=예수금");
    }

    @Test
    void 근거문에_환율과_환산액과_예수금이_남는다() {
        // AC8
        String note = OrderSizer.buyQuantity("US", BUDGET, usd("41.29"), FX, CASH_1500).note();
        assertThat(note).contains("환율 1340.78").contains("예산 600000원=$447.50")
                .contains("예수금 $1500").contains("$41.29 x 10주");
    }
}
