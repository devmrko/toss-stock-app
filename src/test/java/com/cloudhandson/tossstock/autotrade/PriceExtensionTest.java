package com.cloudhandson.tossstock.autotrade;

import com.cloudhandson.tossstock.market.DailyOhlcv;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/** #859 — 최근 저점 대비 실시간가 상승률(급등 필터 측정 교정). */
class PriceExtensionTest {

    private static DailyOhlcv bar(String date, long close) {
        DailyOhlcv d = new DailyOhlcv();
        d.setTradeDate(LocalDate.parse(date));
        d.setCloseP(BigDecimal.valueOf(close));
        return d;
    }

    /** 안랩 실측: 10/2 78,400이 5일 최저, 진입 체결가 89,400 → +14.03%. */
    @Test
    void 최근_저점_대비_상승률_계산() {
        List<DailyOhlcv> rows = List.of(
                bar("2026-09-26", 80000), bar("2026-09-29", 79000), bar("2026-09-30", 75400),
                bar("2026-10-01", 77700), bar("2026-10-02", 78400));
        Double pct = PriceExtension.pctAboveRecentLow(rows, BigDecimal.valueOf(89400), 5);
        assertThat(pct).isCloseTo(18.57, within(0.01)); // 저점 75,400 기준
    }

    @Test
    void 룩백_윈도우_밖의_저점은_무시() {
        List<DailyOhlcv> rows = List.of(
                bar("2026-09-26", 10000), // 아주 낮지만 5일 윈도우 밖
                bar("2026-09-29", 20000), bar("2026-09-30", 20000),
                bar("2026-10-01", 20000), bar("2026-10-02", 20000), bar("2026-10-06", 20000));
        Double pct = PriceExtension.pctAboveRecentLow(rows, BigDecimal.valueOf(22000), 5);
        assertThat(pct).isCloseTo(10.0, within(0.01)); // 20,000 기준이지 10,000 기준이 아님
    }

    @Test
    void 데이터가_룩백보다_적으면_있는만큼으로_계산() {
        List<DailyOhlcv> rows = List.of(bar("2026-10-01", 100), bar("2026-10-02", 120));
        assertThat(PriceExtension.pctAboveRecentLow(rows, BigDecimal.valueOf(130), 5))
                .isCloseTo(30.0, within(0.01));
    }

    @Test
    void 현재가가_저점보다_낮으면_음수() {
        List<DailyOhlcv> rows = List.of(bar("2026-10-01", 100), bar("2026-10-02", 120));
        assertThat(PriceExtension.pctAboveRecentLow(rows, BigDecimal.valueOf(90), 5))
                .isCloseTo(-10.0, within(0.01));
    }

    @Test
    void 측정불가면_null_판정보류() {
        assertThat(PriceExtension.pctAboveRecentLow(List.of(), BigDecimal.TEN, 5)).isNull();
        assertThat(PriceExtension.pctAboveRecentLow(null, BigDecimal.TEN, 5)).isNull();
        assertThat(PriceExtension.pctAboveRecentLow(List.of(bar("2026-10-01", 100)), null, 5)).isNull();
        assertThat(PriceExtension.pctAboveRecentLow(List.of(bar("2026-10-01", 0)), BigDecimal.TEN, 5)).isNull();
    }
}
