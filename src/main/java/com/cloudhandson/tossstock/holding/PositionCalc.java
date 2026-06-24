package com.cloudhandson.tossstock.holding;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;

/**
 * 한 종목의 거래목록(BUY/SELL) → 평균단가 기준 집계 {@link PositionView}. 순수 함수.
 * 설계: docs/design/433-trade-ledger/fn-position-calc.md
 */
public final class PositionCalc {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final int MC = 12;  // 중간 계산 스케일

    private PositionCalc() {
    }

    public static PositionView of(String symbol, String name, String sector,
                                  List<Holding> trades, BigDecimal current,
                                  Double change1dPct, Double change7dPct,
                                  BigDecimal peak, BigDecimal trough, LocalDateTime now) {
        List<Holding> sorted = trades.stream()
                .sorted(Comparator.comparing(Holding::getBuyAt, Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();

        // 스탑% = 가장 최근 BUY 값, 최초매수일 = 가장 이른 BUY
        double stopPct = 0;
        LocalDateTime firstBuyAt = null;
        boolean allBuyQty = true;
        for (Holding t : sorted) {
            if (!t.isSell()) {
                if (firstBuyAt == null) {
                    firstBuyAt = t.getBuyAt();
                }
                if (t.getStopPct() != null) {
                    stopPct = t.getStopPct();
                }
                if (t.getQuantity() == null) {
                    allBuyQty = false;
                }
            }
        }

        Long netQty = null;
        BigDecimal avgCost;
        Long unrealizedAmount = null, realizedAmount = null;

        if (allBuyQty) {
            BigDecimal boughtQty = BigDecimal.ZERO, boughtCost = BigDecimal.ZERO;
            BigDecimal totBuyQty = BigDecimal.ZERO, totBuyCost = BigDecimal.ZERO;
            BigDecimal soldQty = BigDecimal.ZERO, realized = BigDecimal.ZERO;
            for (Holding t : sorted) {
                BigDecimal price = t.getBuyPrice();
                BigDecimal q = t.getQuantity() == null ? null : BigDecimal.valueOf(t.getQuantity());
                if (!t.isSell()) {
                    boughtQty = boughtQty.add(q);
                    boughtCost = boughtCost.add(q.multiply(price));
                    totBuyQty = totBuyQty.add(q);
                    totBuyCost = totBuyCost.add(q.multiply(price));
                } else if (q != null) {
                    BigDecimal avg = boughtQty.signum() > 0
                            ? boughtCost.divide(boughtQty, MC, RoundingMode.HALF_UP) : price;
                    realized = realized.add(q.multiply(price.subtract(avg)));
                    boughtCost = boughtCost.subtract(q.multiply(avg));
                    boughtQty = boughtQty.subtract(q);
                    soldQty = soldQty.add(q);
                }
            }
            netQty = boughtQty.setScale(0, RoundingMode.HALF_UP).longValue();
            avgCost = boughtQty.signum() > 0
                    ? boughtCost.divide(boughtQty, 2, RoundingMode.HALF_UP)
                    : (totBuyQty.signum() > 0 ? totBuyCost.divide(totBuyQty, 2, RoundingMode.HALF_UP) : null);
            if (netQty > 0 && current != null && avgCost != null) {
                unrealizedAmount = BigDecimal.valueOf(netQty)
                        .multiply(current.subtract(avgCost)).setScale(0, RoundingMode.HALF_UP).longValue();
            }
            if (soldQty.signum() > 0) {
                realizedAmount = realized.setScale(0, RoundingMode.HALF_UP).longValue();
            }
        } else {
            // 폴백: BUY 가격 단순평균, 수량기반 손익 미산정
            BigDecimal sum = BigDecimal.ZERO;
            int cnt = 0;
            for (Holding t : sorted) {
                if (!t.isSell()) {
                    sum = sum.add(t.getBuyPrice());
                    cnt++;
                }
            }
            avgCost = cnt > 0 ? sum.divide(BigDecimal.valueOf(cnt), 2, RoundingMode.HALF_UP) : null;
        }

        Double unrealizedPct = (avgCost != null && current != null && avgCost.signum() != 0)
                ? pct(current.subtract(avgCost), avgCost) : null;

        BigDecimal stopPrice = avgCost == null ? null
                : avgCost.multiply(BigDecimal.valueOf(1 - stopPct / 100.0)).setScale(2, RoundingMode.HALF_UP);
        Double stopDistPct = (current != null && stopPrice != null && current.signum() != 0)
                ? pct(current.subtract(stopPrice), current) : null;
        boolean belowStop = current != null && stopPrice != null && current.compareTo(stopPrice) <= 0;
        boolean stopHit = trough != null && stopPrice != null && trough.compareTo(stopPrice) <= 0;
        Double ddFromPeak = (peak != null && current != null && peak.signum() != 0)
                ? pct(peak.subtract(current), peak) : null;
        Long daysHeld = firstBuyAt == null ? null : Math.max(0, Duration.between(firstBuyAt, now).toDays());

        // 거래내역: 최신순(집계행 펼침용)
        List<TradeView> tv = sorted.stream()
                .sorted(Comparator.comparing(Holding::getBuyAt, Comparator.nullsFirst(Comparator.naturalOrder())).reversed())
                .map(t -> new TradeView(t.getId(), t.isSell() ? "SELL" : "BUY", t.getBuyAt(), t.getBuyPrice(),
                        t.getQuantity(),
                        (current != null && t.getBuyPrice().signum() != 0)
                                ? pct(current.subtract(t.getBuyPrice()), t.getBuyPrice()) : null))
                .toList();

        return new PositionView(symbol, name, sector, netQty, avgCost, current,
                change1dPct, change7dPct, unrealizedPct, unrealizedAmount, realizedAmount, daysHeld,
                stopPct, stopPrice, stopDistPct, belowStop, peak, ddFromPeak, stopHit, firstBuyAt, tv);
    }

    private static Double pct(BigDecimal num, BigDecimal den) {
        if (den == null || den.signum() == 0) {
            return null;
        }
        return num.divide(den, 6, RoundingMode.HALF_UP).multiply(HUNDRED)
                .setScale(2, RoundingMode.HALF_UP).doubleValue();
    }
}
