package com.cloudhandson.tossstock.market;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * 일봉 시계열 → 시각화 지표(순수). 설계: docs/design/429-market-viz/fn-metrics.md
 * 입력 daily 는 거래일 오름차순.
 */
public final class MarketMetrics {

    private static final int FLOW_N = 20;
    private static final int TREND_WIN = 30;
    private static final int WIN = 20;

    private MarketMetrics() {
    }

    public record Metrics(
            List<Long> flow,        // 최근 N 종가(스파크라인)
            Double trendPct,        // 현재가 vs MA
            Double dipPct,          // 고점대비 낙폭(%)
            Integer swingPos,       // 지지~저항 내 위치(0~100)
            Long swingLow,
            Long swingHigh,
            Integer swings,         // 국소 반전 횟수
            Long tradingValue,      // 거래대금(최신 volume×close)
            Long lastClose) {

        static Metrics empty() {
            return new Metrics(List.of(), null, null, null, null, null, null, null, null);
        }
    }

    /** daily(오름차순) + 최신가(없으면 마지막 종가) → 지표. */
    public static Metrics of(List<DailyOhlcv> daily, BigDecimal lastPrice) {
        if (daily == null || daily.size() < 2) {
            return Metrics.empty();
        }
        List<Long> closes = new ArrayList<>();
        List<Long> highs = new ArrayList<>();
        List<Long> lows = new ArrayList<>();
        for (DailyOhlcv d : daily) {
            if (d.getCloseP() != null) closes.add(d.getCloseP().longValue());
            if (d.getHighP() != null) highs.add(d.getHighP().longValue());
            if (d.getLowP() != null) lows.add(d.getLowP().longValue());
        }
        if (closes.size() < 2) {
            return Metrics.empty();
        }
        long last = lastPrice != null ? lastPrice.longValue() : closes.get(closes.size() - 1);

        List<Long> flow = tail(closes, FLOW_N);

        List<Long> trendCloses = tail(closes, TREND_WIN);
        double ma = trendCloses.stream().mapToLong(Long::longValue).average().orElse(last);
        Double trendPct = ma == 0 ? null : round((last - ma) / ma * 100);

        List<Long> winHighs = tail(highs, WIN);
        List<Long> winLows = tail(lows, WIN);
        long hi = winHighs.stream().mapToLong(Long::longValue).max().orElse(last);
        long lo = winLows.stream().mapToLong(Long::longValue).min().orElse(last);

        Double dipPct = hi == 0 ? null : round(Math.max(0, (hi - last) * 100.0 / hi));

        Integer swingPos = (hi == lo) ? 50
                : (int) Math.round(Math.max(0, Math.min(100, (last - lo) * 100.0 / (hi - lo))));

        DailyOhlcv lastBar = daily.get(daily.size() - 1);
        Long tradingValue = (lastBar.getVolume() != null && lastBar.getCloseP() != null)
                ? lastBar.getVolume() * lastBar.getCloseP().longValue() : null;

        return new Metrics(flow, trendPct, dipPct, swingPos, lo, hi,
                swings(tail(closes, WIN)), tradingValue, last);
    }

    /** 국소 반전(고→저→고) 횟수 — 밴드 신뢰도 가늠. */
    static int swings(List<Long> v) {
        if (v.size() < 3) {
            return 0;
        }
        int count = 0;
        int dir = 0; // 1 상승, -1 하락
        for (int i = 1; i < v.size(); i++) {
            int d = Long.compare(v.get(i), v.get(i - 1));
            if (d == 0) continue;
            if (dir != 0 && d != dir) count++;
            dir = d;
        }
        return count;
    }

    private static List<Long> tail(List<Long> v, int n) {
        return v.size() <= n ? v : v.subList(v.size() - n, v.size());
    }

    private static double round(double x) {
        return BigDecimal.valueOf(x).setScale(2, RoundingMode.HALF_UP).doubleValue();
    }
}
