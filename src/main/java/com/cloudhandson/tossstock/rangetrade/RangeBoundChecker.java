package com.cloudhandson.tossstock.rangetrade;

import com.cloudhandson.tossstock.market.DailyOhlcv;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 일봉 구간이 "추세 없이 일정 밴드 안에서 반복 왕복하는 박스권"인지 판정하고 밴드[저,고]를 산출(순수 함수).
 * 설계: docs/design/818-range-trade-swing/fn-range-bound-checker.md
 */
public final class RangeBoundChecker {

    private static final BigDecimal TWO = BigDecimal.valueOf(2);
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private RangeBoundChecker() {
    }

    /** 판정 결과. isRangeBound=false 면 low/high 는 null. */
    public record Result(boolean isRangeBound, BigDecimal low, BigDecimal high) {

        static Result rejected() {
            return new Result(false, null, null);
        }
    }

    /**
     * @param window 한 종목의 최근 일봉(정렬 여부 무관 — 내부에서 날짜순 정렬)
     * @param props  밴드 폭/추세 드리프트 임계값
     */
    public static Result evaluate(List<DailyOhlcv> window, RangeTradeProperties props) {
        List<DailyOhlcv> bars = usableBars(window);
        if (bars.size() < props.requiredBars()) {
            return Result.rejected(); // 데이터 부족 → fail-closed(§6)
        }
        bars.sort(Comparator.comparing(DailyOhlcv::getTradeDate));

        BigDecimal low = bars.get(0).getLowP();
        BigDecimal high = bars.get(0).getHighP();
        for (DailyOhlcv b : bars) {
            low = low.min(b.getLowP());
            high = high.max(b.getHighP());
        }
        double widthPct = pctSpread(high, low);
        if (widthPct < props.minWidthPct() || widthPct > props.maxWidthPct()) {
            return Result.rejected(); // 너무 좁음(거래비용 대비 안 남음) 또는 너무 넓음(박스권 아님)
        }
        if (trendDriftPct(bars) > props.maxTrendDriftPct()) {
            return Result.rejected(); // 한 방향으로 흘러간 완만한 추세 — 박스권 아님
        }
        return new Result(true, low, high);
    }

    /** 계산에 쓸 수 있는 일봉만(고가/저가/종가/거래일 중 하나라도 null 이면 제외, §6). */
    private static List<DailyOhlcv> usableBars(List<DailyOhlcv> window) {
        List<DailyOhlcv> out = new ArrayList<>();
        if (window == null) {
            return out;
        }
        for (DailyOhlcv b : window) {
            // 종가도 필수 — 전반부/후반부 평균(추세 드리프트) 계산에 쓰임.
            if (b != null && b.getHighP() != null && b.getLowP() != null
                    && b.getCloseP() != null && b.getTradeDate() != null) {
                out.add(b);
            }
        }
        return out;
    }

    /**
     * 추세 드리프트(%) — 윈도우를 전반부/후반부로 나눠 종가 평균의 차이를 중간값 대비 비율로.
     * 홀수개면 중간 1개는 전반부(과거쪽)에 포함(§7).
     */
    private static double trendDriftPct(List<DailyOhlcv> sortedBars) {
        int n = sortedBars.size();
        int firstHalfEnd = (n + 1) / 2; // ceil(n/2)
        BigDecimal firstAvg = avgClose(sortedBars.subList(0, firstHalfEnd));
        BigDecimal secondAvg = avgClose(sortedBars.subList(firstHalfEnd, n));
        return Math.abs(pctSpread(secondAvg, firstAvg));
    }

    private static BigDecimal avgClose(List<DailyOhlcv> bars) {
        BigDecimal sum = BigDecimal.ZERO;
        for (DailyOhlcv b : bars) {
            sum = sum.add(b.getCloseP());
        }
        return sum.divide(BigDecimal.valueOf(bars.size()), MathContext.DECIMAL64);
    }

    /** (a - b) / ((a + b) / 2) * 100. 두 값의 중간값 대비 차이 비율. */
    private static double pctSpread(BigDecimal a, BigDecimal b) {
        BigDecimal mid = a.add(b).divide(TWO, MathContext.DECIMAL64);
        if (mid.signum() == 0) {
            return 0.0;
        }
        return a.subtract(b).divide(mid, MathContext.DECIMAL64).multiply(HUNDRED).doubleValue();
    }
}
