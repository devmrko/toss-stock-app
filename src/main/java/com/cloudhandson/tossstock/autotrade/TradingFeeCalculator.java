package com.cloudhandson.tossstock.autotrade;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 수수료/세금 추정치 계산(순수 함수, #847 — #841 정정). 실측(2026-10-07): 토스
 * /api/v1/orders/{id} 응답의 commission/tax는 체결 직후~5일 뒤(정산 이후)까지 항상 "0"
 * (13건 확인) — 그 엔드포인트엔 실제 값이 없다. 실제 요율은 /api/v1/holdings의
 * item.cost와 /api/v1/commissions(요율표)에서 확인: 보유 5종목 교차검증 결과 세금이
 * 소수 4자리까지 동일하게 "매도금액의 0.20%"로 일치했다.
 *
 * 이 값은 토스가 확정한 공식 금액이 아니라 요율 기반 **추정치**다(반올림/최소수수료 등
 * 미세 차이 가능) — 그래도 기존의 "항상 0"보다는 훨씬 정확하다.
 */
public final class TradingFeeCalculator {

    /** KR 수수료율(매수/매도 공통) — /api/v1/commissions 실측(2021-01-01~9999-12-31, 변경 드묾). */
    private static final BigDecimal KR_COMMISSION_RATE = new BigDecimal("0.00015");

    /** US 수수료율 — /api/v1/commissions 실측. endDate가 2026-10-08로 찍혀 있어(실측 시점
     * 기준 임박) 만료 후 요율이 바뀌면 재확인 필요(§11, 이 상수 갱신 대상). */
    private static final BigDecimal US_COMMISSION_RATE = new BigDecimal("0.001");

    /** KR 세금율(매도에만 적용) — 보유 5종목 교차검증(086450/006400/053800/003670/066570)
     * 전부 소수 4자리까지 0.2000%로 일치. */
    private static final BigDecimal KR_TAX_RATE = new BigDecimal("0.002");

    private TradingFeeCalculator() {
    }

    /** 수수료(매수/매도 공통, 반올림). filledAmount가 null/0 이하면 0. */
    public static BigDecimal commission(BigDecimal filledAmount, String market) {
        if (filledAmount == null || filledAmount.signum() <= 0) {
            return BigDecimal.ZERO;
        }
        BigDecimal rate = "US".equalsIgnoreCase(market) ? US_COMMISSION_RATE : KR_COMMISSION_RATE;
        return filledAmount.multiply(rate).setScale(0, RoundingMode.HALF_UP);
    }

    /**
     * 세금(매도에만, KR만 — 실측 확인된 범위). US는 아직 요율 미확인이라 0(과소추정
     * 가능성 있음 — §9, 숨기지 않고 명시).
     */
    public static BigDecimal tax(BigDecimal filledAmount, String market, String side) {
        if (filledAmount == null || filledAmount.signum() <= 0) {
            return BigDecimal.ZERO;
        }
        if (!"SELL".equalsIgnoreCase(side) || "US".equalsIgnoreCase(market)) {
            return BigDecimal.ZERO;
        }
        return filledAmount.multiply(KR_TAX_RATE).setScale(0, RoundingMode.HALF_UP);
    }
}
