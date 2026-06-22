package com.cloudhandson.tossstock.market;

import com.cloudhandson.tossstock.toss.TossApiClient;
import com.cloudhandson.tossstock.toss.TossApiClient.CandlePage;
import com.cloudhandson.tossstock.toss.dto.TossCandle;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DailyCollectorTest {

    @Mock UniverseMapper universeMapper;
    @Mock DailyOhlcvMapper dailyMapper;
    @Mock VolumeRankWriter writer;
    @Mock TossApiClient toss;
    @Mock ScanStatus status;
    @InjectMocks DailyCollector collector;

    private static TossCandle candle(String date, String close, String vol) {
        return new TossCandle(date + "T00:00:00.000+09:00", "1", "1", "1", close, vol, "KRW");
    }

    @Test
    void toDaily_parses_date_and_numbers() {
        DailyOhlcv d = collector.toDaily("005930",
                new TossCandle("2026-06-22T00:00:00.000+09:00", "351000", "363000", "342000", "352500", "43030480", "KRW"));
        assertThat(d.getSymbol()).isEqualTo("005930");
        assertThat(d.getTradeDate()).isEqualTo(LocalDate.of(2026, 6, 22));
        assertThat(d.getOpenP()).isEqualByComparingTo("351000");
        assertThat(d.getHighP()).isEqualByComparingTo("363000");
        assertThat(d.getCloseP()).isEqualByComparingTo("352500");
        assertThat(d.getVolume()).isEqualTo(43030480L);
    }

    @Test
    void changeRate_basic_and_guards() {
        assertThat(DailyCollector.changeRate(new BigDecimal("352500"), new BigDecimal("350500"))).isEqualTo(0.57);
        assertThat(DailyCollector.changeRate(new BigDecimal("100"), BigDecimal.ZERO)).isNull();
        assertThat(DailyCollector.changeRate(null, new BigDecimal("100"))).isNull();
    }

    @Test
    void fetchYear_stops_at_cutoff_and_filters_old() {
        // page1 oldest = 2년 전 → cutoff(1년 전)보다 과거이므로 1페이지에서 종료
        CandlePage page1 = new CandlePage(List.of(
                candle("2026-06-22", "100", "10"),
                candle("2025-12-01", "100", "10"),
                candle("2024-01-02", "100", "10")   // 1년 경계 밖
        ), "2024-01-01T00:00:00.000+09:00");
        when(toss.getDailyCandlePage(eq("005930"), eq(200), isNull())).thenReturn(page1);

        List<TossCandle> got = collector.fetchYear("005930");

        // 한 페이지만 호출(컷오프 도달로 종료)
        verify(toss, times(1)).getDailyCandlePage(eq("005930"), eq(200), isNull());
        // 1년 이내만 남김 → 2024-01-02 제외
        assertThat(got).hasSize(2);
        assertThat(got).noneMatch(c -> c.timestamp().startsWith("2024"));
    }

    @Test
    void materializeTop50_assigns_rank_and_change() {
        VolumeRank a = new VolumeRank();
        a.setSymbol("AAA"); a.setLastPrice(new BigDecimal("110")); a.setVolume(300L);
        VolumeRank b = new VolumeRank();
        b.setSymbol("BBB"); b.setLastPrice(new BigDecimal("90")); b.setVolume(200L);
        when(dailyMapper.latestDate()).thenReturn(LocalDate.of(2026, 6, 22));
        when(dailyMapper.topByVolumeOnLatest(50)).thenReturn(List.of(a, b));
        DailyOhlcv pa = new DailyOhlcv(); pa.setSymbol("AAA"); pa.setCloseP(new BigDecimal("100"));
        DailyOhlcv pb = new DailyOhlcv(); pb.setSymbol("BBB"); pb.setCloseP(new BigDecimal("100"));
        when(dailyMapper.prevCloseBefore(LocalDate.of(2026, 6, 22))).thenReturn(List.of(pa, pb));

        collector.materializeTop50();

        assertThat(a.getRnk()).isEqualTo(1);
        assertThat(a.getChangeRate()).isEqualTo(10.0);   // 110 vs 100
        assertThat(b.getRnk()).isEqualTo(2);
        assertThat(b.getChangeRate()).isEqualTo(-10.0);  // 90 vs 100
        verify(writer).replaceAll(eq(List.of(a, b)), org.mockito.ArgumentMatchers.any());
    }
}
