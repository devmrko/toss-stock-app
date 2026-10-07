package com.cloudhandson.tossstock.autotrade;

import java.math.BigDecimal;

/**
 * 저평가 판정(순수 함수) — PER/PBR이 둘 다 임계값 이하일 때만 저평가로 본다.
 * 절대 임계값 방식(섹터 평균 대비 방식은 데이터 소스 제약으로 v1 범위 밖 — README §12).
 * 데이터 누락/음수(적자 등)는 안전 쪽(저평가 아님)으로 처리 — 실 자금이 걸린 판정이라 fail-closed.
 */
public final class ValuationChecker {

    private ValuationChecker() {
    }

    public static boolean isUndervalued(Valuation v, double maxPer, double maxPbr) {
        if (v == null || v.per() == null || v.pbr() == null) {
            return false;
        }
        if (v.per().signum() <= 0 || v.pbr().signum() <= 0) {
            return false; // 적자(PER 음수) 등은 저평가 판정 대상 아님
        }
        return v.per().doubleValue() <= maxPer && v.pbr().doubleValue() <= maxPbr;
    }

    /**
     * 재평가촉매(rerateCatalyst)로 저평가·급등 필터를 면제해 줘도 되는 밸류에이션 범위인지(#855).
     * 실측(2026-10-08): 포스코퓨처엠 PER 392.49와 삼성SDI PER 데이터없음이 촉매 키워드 하나로
     * 두 필터를 모두 면제받고 매수돼 각각 -3.83%/-2.11%. 이미 기대가 극단적으로 들어가 있는
     * 가격엔 어떤 촉매도 추가 상승을 정당화하지 못한다고 보고 상한을 건다.
     * 판정 불가(데이터 없음·적자)는 면제 불가 — {@link #isUndervalued}와 동일한 fail-closed.
     */
    public static boolean withinCatalystBound(Valuation v, double perBound, double pbrBound) {
        if (v == null || v.per() == null || v.pbr() == null) {
            return false;
        }
        if (v.per().signum() <= 0 || v.pbr().signum() <= 0) {
            return false;
        }
        return v.per().doubleValue() <= perBound && v.pbr().doubleValue() <= pbrBound;
    }
}
