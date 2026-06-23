package com.cloudhandson.tossstock.holding;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;

/** 보유 지표 계산(순수). 설계: docs/design/430-holdings/fn-holding-calc.md */
public final class HoldingCalc {

    private HoldingCalc() {
    }

    public static HoldingView of(Holding h, String name, String sector,
                                 BigDecimal current, BigDecimal peak, BigDecimal trough, LocalDateTime now) {
        BigDecimal buy = h.getBuyPrice();
        double stopPct = h.getStopPct() == null ? 0 : h.getStopPct();

        BigDecimal stopPrice = buy.multiply(BigDecimal.valueOf(1 - stopPct / 100.0))
                .setScale(2, RoundingMode.HALF_UP);

        Double returnPct = (current != null && buy.signum() != 0)
                ? pct(current.subtract(buy), buy) : null;
        Long returnAmount = (current != null && h.getQuantity() != null)
                ? current.subtract(buy).multiply(BigDecimal.valueOf(h.getQuantity())).longValue() : null;

        Double stopDistPct = (current != null && current.signum() != 0)
                ? pct(current.subtract(stopPrice), current) : null;
        boolean belowStop = current != null && current.compareTo(stopPrice) <= 0;

        Long daysHeld = h.getBuyAt() == null ? null
                : Math.max(0, Duration.between(h.getBuyAt(), now).toDays());

        Double ddFromPeak = (peak != null && current != null && peak.signum() != 0)
                ? pct(peak.subtract(current), peak) : null;
        boolean stopHit = trough != null && trough.compareTo(stopPrice) <= 0;

        return new HoldingView(h.getId(), h.getSymbol(), name, sector, h.getBuyAt(), buy,
                h.getQuantity(), h.getStopPct(), h.getMemo(),
                current, returnPct, returnAmount, daysHeld, stopPrice, stopDistPct,
                belowStop, peak, ddFromPeak, stopHit);
    }

    private static Double pct(BigDecimal num, BigDecimal den) {
        if (den == null || den.signum() == 0) {
            return null;
        }
        return num.divide(den, 6, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100)).setScale(2, RoundingMode.HALF_UP).doubleValue();
    }
}
