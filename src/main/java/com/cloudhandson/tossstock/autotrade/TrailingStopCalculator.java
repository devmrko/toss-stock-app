package com.cloudhandson.tossstock.autotrade;

import java.math.BigDecimal;
import java.math.MathContext;

/**
 * 하드손절/추적손절 매도 판정(순수 함수). 원칙 §4, 대화 중 시뮬레이션(simulate_trailing_stop.py)과 동일 공식.
 * 설계: docs/design/808-auto-trade-engine/fn-trailing-stop.md
 */
public final class TrailingStopCalculator {

    private TrailingStopCalculator() {
    }

    public static ExitReason decide(BigDecimal current, BigDecimal peak, BigDecimal avgCost,
                                     double hardStopPct, double trailPct) {
        if (current == null || current.signum() <= 0) {
            throw new IllegalArgumentException("current must be > 0: " + current);
        }
        if (avgCost == null || avgCost.signum() <= 0) {
            throw new IllegalArgumentException("avgCost must be > 0: " + avgCost);
        }
        if (peak == null || peak.compareTo(avgCost) < 0) {
            throw new IllegalArgumentException("peak must be >= avgCost: peak=" + peak + ", avgCost=" + avgCost);
        }

        BigDecimal hardFloor = hardFloor(avgCost, hardStopPct);
        BigDecimal trailFloor = trailFloor(peak, trailPct);
        BigDecimal stopPrice = hardFloor.max(trailFloor);

        if (current.compareTo(stopPrice) > 0) {
            return ExitReason.NONE;
        }
        boolean hitViaHard = current.compareTo(hardFloor) <= 0 && hardFloor.compareTo(trailFloor) >= 0;
        return hitViaHard ? ExitReason.HARD_STOP : ExitReason.TRAIL_STOP;
    }

    /**
     * 하드손절선 — 평단 - hardStopPct%. {@link #decide}가 쓰는 바로 그 값이며, 결정근거 로깅(#832)에서
     * "어느 쪽이 바인딩이었나"를 남기려고 공개했다(판정 로직은 바뀌지 않음).
     */
    public static BigDecimal hardFloor(BigDecimal avgCost, double hardStopPct) {
        return avgCost.multiply(BigDecimal.valueOf(1 - hardStopPct / 100.0), MathContext.DECIMAL64);
    }

    /** 추적손절선 — 보유 중 최고가 - trailPct%. 용도는 {@link #hardFloor}와 동일. */
    public static BigDecimal trailFloor(BigDecimal peak, double trailPct) {
        return peak.multiply(BigDecimal.valueOf(1 - trailPct / 100.0), MathContext.DECIMAL64);
    }

    /**
     * 적용할 추적손절폭 — 원칙 §4 "대박 구간(+300% 등)은 -15~20%로 완화 가능"(#873).
     * 멀티배거를 10% 흔들림에 털리지 않게 하는 조항이다.
     *
     * <p>측정 기준은 <b>피크</b>다(현재가 아님) — 현재가로 재면 가격이 오갈 때 추적폭이
     * 깜빡인다. 한 번 멀티배거 구간에 들어가면 완화를 유지한다.
     *
     * <p>호출부는 이 값을 한 번 해소해 {@link #decide}와 {@link #trailFloor}에 <b>같은 값</b>을
     * 넘겨야 한다 — 그러지 않으면 "15%로 판정했는데 로그엔 10% 손절선"이 찍혀 사후 검증이 깨진다.
     *
     * @param peak               보유 중 최고가
     * @param avgCost            진입가(평단). null·0 이하면 기본폭(방어)
     * @param trailPct           기본 추적폭(예: 10.0)
     * @param multibaggerGainPct 완화 임계 수익률(예: 300.0 = 피크가 진입가의 4배)
     * @param multibaggerTrailPct 완화 후 추적폭(예: 15.0)
     */
    public static double effectiveTrailPct(BigDecimal peak, BigDecimal avgCost, double trailPct,
                                            double multibaggerGainPct, double multibaggerTrailPct) {
        if (peak == null || avgCost == null || avgCost.signum() <= 0) {
            return trailPct;
        }
        double gainPct = peak.divide(avgCost, MathContext.DECIMAL64)
                .subtract(BigDecimal.ONE).doubleValue() * 100;
        return gainPct >= multibaggerGainPct ? multibaggerTrailPct : trailPct;
    }
}
