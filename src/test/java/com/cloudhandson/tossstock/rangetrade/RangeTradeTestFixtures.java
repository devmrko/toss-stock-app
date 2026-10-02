package com.cloudhandson.tossstock.rangetrade;

import com.cloudhandson.tossstock.market.DailyOhlcv;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/** 레인지 트랙 테스트 공용 픽스처 — 운영 application.yml 과 동일한 파라미터로 테스트한다. */
final class RangeTradeTestFixtures {

    private RangeTradeTestFixtures() {
    }

    /** 운영 기본값(application.yml range-trade)과 동일. dryRun 만 달리 줄 수 있음. */
    static RangeTradeProperties props(boolean dryRun) {
        return new RangeTradeProperties(dryRun, BigDecimal.valueOf(1_000_000), 2, BigDecimal.valueOf(500_000),
                15.0, 60, 26.0, 50.0, 15.0, 10.0, 10.0, 5.0, BigDecimal.valueOf(300_000_000), 30, "",
                "0 0 16 * * MON-FRI");
    }

    static RangeTradeProperties props() {
        return props(true);
    }

    /** 고가/저가/종가를 직접 지정한 일봉 n개(날짜는 과거→최근). */
    static List<DailyOhlcv> bars(int n, BarFactory factory) {
        List<DailyOhlcv> out = new ArrayList<>();
        LocalDate start = LocalDate.of(2026, 1, 5);
        for (int i = 0; i < n; i++) {
            out.add(factory.create(i, start.plusDays(i)));
        }
        return out;
    }

    /** 밴드 [low, high] 안에서 종가가 왕복하는 일봉(추세 없음). */
    static List<DailyOhlcv> oscillating(int n, double low, double high) {
        return bars(n, (i, date) -> bar(date, high, low, i % 2 == 0 ? low : high));
    }

    static DailyOhlcv bar(LocalDate date, double high, double low, double close) {
        return new DailyOhlcv("TEST", date, BigDecimal.valueOf(close), BigDecimal.valueOf(high),
                BigDecimal.valueOf(low), BigDecimal.valueOf(close), 1_000_000L);
    }

    interface BarFactory {
        DailyOhlcv create(int index, LocalDate date);
    }
}
