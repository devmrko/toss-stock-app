package com.cloudhandson.tossstock.watchlist;

import com.cloudhandson.tossstock.toss.CandleCache;
import com.cloudhandson.tossstock.toss.TossApiClient;
import com.cloudhandson.tossstock.toss.TossApiException;
import com.cloudhandson.tossstock.toss.dto.TossCandle;
import com.cloudhandson.tossstock.toss.dto.TossPrice;
import com.cloudhandson.tossstock.toss.dto.TossStock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WatchlistQuoteServiceTest {

    @Mock WatchlistMapper mapper;
    @Mock TossApiClient toss;
    @Mock CandleCache candleCache;
    @Mock com.cloudhandson.tossstock.market.UniverseMapper universeMapper;
    @InjectMocks WatchlistQuoteService service;

    private static Watchlist wl(long id, String symbol) {
        Watchlist w = new Watchlist();
        w.setId(id);
        w.setSymbol(symbol);
        return w;
    }

    private static TossPrice price(String symbol, String last) {
        return new TossPrice(symbol, last, "KRW", "2026-06-22T12:00:00+09:00");
    }

    private static TossStock stock(String symbol, String name) {
        return new TossStock(symbol, name, name, "KOSPI", "ISIN", "STOCK", "ACTIVE", "KRW");
    }

    private static List<TossCandle> candles(String volume, String prevClose) {
        return List.of(
                new TossCandle("2026-06-22T00:00:00+09:00", "1", "1", "1", "1", volume, "KRW"),
                new TossCandle("2026-06-19T00:00:00+09:00", "1", "1", "1", prevClose, "1", "KRW"));
    }

    @Test
    void sorts_by_volume_desc_and_assigns_rank() {
        when(mapper.findAll()).thenReturn(List.of(wl(1, "AAA"), wl(2, "BBB"), wl(3, "CCC")));
        when(toss.getPrices(List.of("AAA", "BBB", "CCC")))
                .thenReturn(List.of(price("AAA", "100"), price("BBB", "200"), price("CCC", "300")));
        when(toss.getStocks(List.of("AAA", "BBB", "CCC")))
                .thenReturn(List.of(stock("AAA", "에이"), stock("BBB", "비"), stock("CCC", "씨")));
        when(candleCache.get("AAA")).thenReturn(candles("100", "90"));
        when(candleCache.get("BBB")).thenReturn(candles("300", "190"));
        when(candleCache.get("CCC")).thenReturn(candles("200", "290"));

        List<WatchlistQuote> result = service.assemble();

        assertThat(result).extracting(WatchlistQuote::symbol).containsExactly("BBB", "CCC", "AAA");
        assertThat(result).extracting(WatchlistQuote::rank).containsExactly(1, 2, 3);
        assertThat(result).extracting(WatchlistQuote::volume).containsExactly(300L, 200L, 100L);
    }

    @Test
    void computes_change_rate_from_prev_close() {
        WatchlistQuote q = service.toRow(wl(1, "005930"), price("005930", "352500"),
                "삼성전자", "반도체", candles("43030480", "350500"));

        assertThat(q.lastPrice()).isEqualByComparingTo("352500");
        assertThat(q.prevClose()).isEqualByComparingTo("350500");
        assertThat(q.changeAmount()).isEqualByComparingTo("2000");
        assertThat(q.changeRate()).isEqualTo(0.57); // 2000/350500*100 = 0.5706 → 0.57
        assertThat(q.volume()).isEqualTo(43030480L);
    }

    @Test
    void null_prev_close_yields_null_change() {
        List<TossCandle> single = List.of(
                new TossCandle("2026-06-22T00:00:00+09:00", "1", "1", "1", "1", "500", "KRW"));
        WatchlistQuote q = service.toRow(wl(1, "NEW"), price("NEW", "1000"), "신규", null, single);

        assertThat(q.changeRate()).isNull();
        assertThat(q.changeAmount()).isNull();
        assertThat(q.volume()).isEqualTo(500L);
    }

    @Test
    void empty_watchlist_returns_empty() {
        when(mapper.findAll()).thenReturn(List.of());
        assertThat(service.assemble()).isEmpty();
    }

    @Test
    void partial_candle_failure_marks_row_stale_not_whole() {
        when(mapper.findAll()).thenReturn(List.of(wl(1, "AAA"), wl(2, "BBB")));
        when(toss.getPrices(List.of("AAA", "BBB")))
                .thenReturn(List.of(price("AAA", "100"), price("BBB", "200")));
        lenient().when(toss.getStocks(List.of("AAA", "BBB")))
                .thenReturn(List.of(stock("AAA", "에이"), stock("BBB", "비")));
        when(candleCache.get("AAA")).thenReturn(candles("100", "90"));
        when(candleCache.get("BBB")).thenThrow(new TossApiException("boom", 502));

        List<WatchlistQuote> result = service.assemble();

        assertThat(result).hasSize(2);
        WatchlistQuote bbb = result.stream().filter(q -> q.symbol().equals("BBB")).findFirst().orElseThrow();
        assertThat(bbb.stale()).isTrue();
        assertThat(bbb.volume()).isNull();
        WatchlistQuote aaa = result.stream().filter(q -> q.symbol().equals("AAA")).findFirst().orElseThrow();
        assertThat(aaa.stale()).isFalse();
    }
}
