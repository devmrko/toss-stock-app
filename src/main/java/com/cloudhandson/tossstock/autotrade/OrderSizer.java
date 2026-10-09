package com.cloudhandson.tossstock.autotrade;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 주문 수량과 결정근거(순수 함수, #886) — <b>리스크 경로</b>라 I/O 를 섞지 않는다.
 *
 * <p><b>왜 분리했나.</b> 수량 계산이 {@code OrderExecutor} 안에 한 줄로 묻혀 있었고,
 * 그 한 줄이 원화 예산을 달러 주가로 그대로 나누고 있었다:
 * {@code 600,000 ÷ $456.35 = 1,315주 ≈ $600,000 ≈ 8억원}. 실제 USD 예수금은 $1,500 이다.
 * 환율 변환 코드가 코드베이스 전체에 없었고, 구현돼 있던
 * {@code TossApiClient.getBuyingPower} 는 매수 경로에서 호출되지 않았다.
 *
 * <p>사고가 안 난 이유는 #885(US 최신 일봉이 미완성 바)가 인기 게이트에서 US 를 전부 막아
 * US 주문로그가 0행이었기 때문이다 — 결함이 결함에 가려져 있었다.
 *
 * 설계: docs/design/886-us-fx-order-sizing/fn-buyQuantity.md
 */
public final class OrderSizer {

    private OrderSizer() {
    }

    /**
     * @param qty  주문 수량(정수, 내림). <b>0 이면 호출부가 주문하지 않는다</b>
     * @param note 주문로그·알림에 그대로 들어가는 1줄 근거
     */
    public record Sizing(BigDecimal qty, String note) {

        static Sizing none(String reason) {
            return new Sizing(BigDecimal.ZERO, reason);
        }
    }

    /**
     * 시장별 매수 수량. US 는 환율로 원화예산을 환산하고 USD 예수금을 상한으로 쓴다.
     *
     * <p><b>왜 최솟값인가.</b> 원화 환산액만 보면 계좌에 달러가 없을 때 주문 거부나
     * 의도치 않은 환전이 날 수 있고, 예수금만 보면 "슬롯당 60만원" 리스크 규율이 깨진다.
     * 두 제약을 동시에 만족하는 유일한 값이 최솟값이다.
     *
     * <p><b>왜 fail-closed 인가.</b> 환율·예수금을 모르는 채 주문하면 수량이 400배로 틀릴
     * 수 있다(이 함수가 고치려는 결함 그 자체). 보류의 비용은 기회비용뿐이라 비대칭이 명확하다.
     *
     * @param market    US 만 특별 취급. 그 외는 원화 경로(기존 동작 유지)
     * @param budgetKrw 슬롯예산(원)
     * @param price     현재가. <b>US 는 달러</b>
     * @param fxUsdKrw  USD/KRW. US 에서 null·0 이하면 수량 0
     * @param cashUsd   USD 예수금. US 에서 null·음수면 수량 0
     */
    public static Sizing buyQuantity(String market, BigDecimal budgetKrw, BigDecimal price,
                                      BigDecimal fxUsdKrw, BigDecimal cashUsd) {
        if (price == null || price.signum() <= 0) {
            return Sizing.none("현재가 없음 — 수량 계산 불가");
        }
        if (budgetKrw == null || budgetKrw.signum() <= 0) {
            return Sizing.none("슬롯예산 없음");
        }
        if (!"US".equalsIgnoreCase(market)) {
            BigDecimal qty = budgetKrw.divide(price, 0, RoundingMode.DOWN);
            return new Sizing(qty, String.format("예산 %s원 ÷ %s원 = %s주",
                    budgetKrw.toPlainString(), price.toPlainString(), qty.toPlainString()));
        }
        if (fxUsdKrw == null || fxUsdKrw.signum() <= 0) {
            return Sizing.none("환율 조회 실패 — US 매수 보류(#886)");
        }
        if (cashUsd == null || cashUsd.signum() < 0) {
            return Sizing.none("USD 예수금 조회 실패 — US 매수 보류(#886)");
        }
        BigDecimal budgetUsd = budgetKrw.divide(fxUsdKrw, 2, RoundingMode.DOWN);
        BigDecimal spendUsd = budgetUsd.min(cashUsd);
        BigDecimal qty = spendUsd.divide(price, 0, RoundingMode.DOWN);
        String cap = budgetUsd.compareTo(cashUsd) <= 0 ? "예산" : "예수금";
        return new Sizing(qty, String.format(
                "환율 %s · 예산 %s원=$%s · 예수금 $%s · 상한=%s · $%s x %s주",
                fxUsdKrw.toPlainString(), budgetKrw.toPlainString(), budgetUsd.toPlainString(),
                cashUsd.toPlainString(), cap, price.toPlainString(), qty.toPlainString()));
    }
}
