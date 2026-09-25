package com.cloudhandson.tossstock.autotrade;

/**
 * 원칙 §3-4 "자본배분" — 최근 결산 배당 지급 여부로 판정(v1). 자사주매입/소각까지 보려면
 * 공시 이력 데이터가 필요한데 지금은 없음 — 배당만으로는 불완전한 대리지표라는 점 명시
 * (README §12 미해결 질문).
 */
public final class CapitalReturnChecker {

    private CapitalReturnChecker() {
    }

    public static boolean paysDividend(AnnualFinancials f) {
        if (f == null || f.latest() == null || f.latest().dividendPerShare() == null) {
            return false;
        }
        return f.latest().dividendPerShare().signum() > 0;
    }
}
