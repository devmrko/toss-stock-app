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
}
