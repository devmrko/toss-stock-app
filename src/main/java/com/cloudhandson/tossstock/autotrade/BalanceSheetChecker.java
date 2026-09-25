package com.cloudhandson.tossstock.autotrade;

/**
 * 원칙 §3-3 "재무건전성" — 최근 결산 부채비율이 임계값 이하인지만 자동판정.
 * "유증/회사채 남발 없음"은 공시 이력 데이터가 없어 자동화 범위 밖(수동 확인 필요, README §12).
 */
public final class BalanceSheetChecker {

    private BalanceSheetChecker() {
    }

    public static boolean isHealthy(AnnualFinancials f, double maxDebtRatio) {
        if (f == null || f.latest() == null || f.latest().debtRatio() == null) {
            return false;
        }
        return f.latest().debtRatio().doubleValue() <= maxDebtRatio;
    }
}
