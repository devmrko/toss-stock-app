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

    /**
     * US 매도 제비용(#861, 2026-10-08 확인) — SEC Section 31 fee: $20.60 per $1,000,000.
     * 출처 3곳 일치: SEC 공식 fee rate advisory(2026-02-27), Federal Register 명령
     * (2026-03-04, "shall be $20.60 per $1,000,000 effective on April 4, 2026"), FINRA
     * Information Notice 3/17/26. 매도에만 부과(covered sales).
     *
     * FINRA TAF(통상 주당 $0.000195, 건당 상·하한 있음)는 <b>2026-10-01~12-31 한시적으로
     * $0.00</b>이라 지금은 0으로 둔다(Federal Register 2026-09-23, FINRA 규칙변경 공시).
     * <b>2027-01-01부터 재개</b>되므로 그때 다시 반영해야 한다 — Redmine #862로 추적.
     *
     * 주의: KR 요율과 달리 토스 실거래로 검증하지 못했다(미국 체결 이력 0건). 규제기관
     * 공시 요율 기반 추정치이며, 토스가 추가 제비용을 붙이는지는 첫 체결 후 대조 필요.
     */
    private static final BigDecimal US_SEC_FEE_RATE = new BigDecimal("0.0000206");

    private TradingFeeCalculator() {
    }

    /**
     * 수수료(매수/매도 공통). filledAmount가 null/0 이하면 0.
     * 반올림 자리수는 통화 단위에 맞춘다 — KR은 정수(원), US는 소수 2자리(센트).
     * (#861에서 발견: 정수 반올림이면 $0.50 수수료가 $1로 잡혀 100% 과대계상됨.)
     */
    public static BigDecimal commission(BigDecimal filledAmount, String market) {
        if (filledAmount == null || filledAmount.signum() <= 0) {
            return BigDecimal.ZERO;
        }
        if ("US".equalsIgnoreCase(market)) {
            return filledAmount.multiply(US_COMMISSION_RATE).setScale(2, RoundingMode.HALF_UP);
        }
        return filledAmount.multiply(KR_COMMISSION_RATE).setScale(0, RoundingMode.HALF_UP);
    }

    /**
     * 세금·제비용(매도에만). KR은 증권거래세 0.20%(실측 확인), US는 SEC Section 31 fee
     * 0.00206%(#861, 규제기관 공시 기반 — 토스 실거래 미검증). 매수는 양쪽 다 0.
     *
     * US는 원 단위가 아니라 달러 단위 금액이 들어오므로 반올림 자리수를 분리한다 —
     * KR은 정수(원), US는 소수 2자리(센트).
     */
    public static BigDecimal tax(BigDecimal filledAmount, String market, String side) {
        if (filledAmount == null || filledAmount.signum() <= 0 || !"SELL".equalsIgnoreCase(side)) {
            return BigDecimal.ZERO;
        }
        if ("US".equalsIgnoreCase(market)) {
            return filledAmount.multiply(US_SEC_FEE_RATE).setScale(2, RoundingMode.HALF_UP);
        }
        return filledAmount.multiply(KR_TAX_RATE).setScale(0, RoundingMode.HALF_UP);
    }
}
