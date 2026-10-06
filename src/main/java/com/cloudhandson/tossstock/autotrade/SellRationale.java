package com.cloudhandson.tossstock.autotrade;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;

/**
 * #808 매도 결정근거 문자열 생성(순수 함수). 호출부(AutoTradeScheduler.processHolding)에서 이미
 * 계산된 값만 받아 포맷한다.
 * 예) {@code 트레일스탑(피크235000→현재210500,-10.43%) 진입200000 수익+5.25% 스탑211500(하드180000/트레일211500)}
 * 설계: docs/design/832-decision-rationale-logging/README.md §6, §7
 */
public final class SellRationale {

    private static final String NA = "N/A";

    private SellRationale() {
    }

    /**
     * @param reason     발동한 매도 사유(null 허용)
     * @param entryPrice 진입가(null 허용)
     * @param peakPrice  보유 중 최고가(null 허용)
     * @param currentPrice 판정에 쓰인 현재가(null 허용)
     * @param hardFloor  하드손절선({@link TrailingStopCalculator#hardFloor}, null 허용)
     * @param trailFloor 추적손절선({@link TrailingStopCalculator#trailFloor}, null 허용)
     */
    public static String describe(ExitReason reason, BigDecimal entryPrice, BigDecimal peakPrice,
                                   BigDecimal currentPrice, BigDecimal hardFloor, BigDecimal trailFloor) {
        return label(reason)
                + "(피크" + plain(peakPrice) + "→현재" + plain(currentPrice)
                + "," + changePct(peakPrice, currentPrice) + ")"
                + " 진입" + plain(entryPrice)
                + " 수익" + changePct(entryPrice, currentPrice)
                + " " + stopPart(hardFloor, trailFloor);
    }

    private static String label(ExitReason reason) {
        if (reason == null) {
            return "매도";
        }
        return switch (reason) {
            case HARD_STOP -> "하드스탑";
            case TRAIL_STOP -> "트레일스탑";
            case NEWS_FADED -> "뉴스소멸";
            case CIRCUIT_BREAKER -> "서킷브레이커";
            case MANUAL -> "수동매도";
            case NONE -> "사유없음";
        };
    }

    /** 바인딩된 스탑(둘 중 높은 값)과 두 후보값을 함께 남긴다 — 어느 쪽이 발동했는지 사후 판별용. */
    private static String stopPart(BigDecimal hardFloor, BigDecimal trailFloor) {
        if (hardFloor == null && trailFloor == null) {
            return "스탑" + NA;
        }
        BigDecimal binding = hardFloor == null ? trailFloor
                : (trailFloor == null ? hardFloor : hardFloor.max(trailFloor));
        return "스탑" + plain(binding) + "(하드" + plain(hardFloor) + "/트레일" + plain(trailFloor) + ")";
    }

    /** from 대비 to 의 변동률. from 이 0·null 이면 N/A(0으로 나누기 방지). */
    private static String changePct(BigDecimal from, BigDecimal to) {
        if (from == null || to == null || from.signum() <= 0) {
            return NA;
        }
        double p = to.subtract(from).divide(from, MathContext.DECIMAL64).doubleValue() * 100;
        return (p >= 0 ? "+" : "") + plain(BigDecimal.valueOf(p)) + "%";
    }

    private static String plain(BigDecimal d) {
        if (d == null) {
            return NA;
        }
        return d.setScale(2, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
    }
}
