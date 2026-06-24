package com.cloudhandson.tossstock.watchlist;

import com.cloudhandson.tossstock.market.Universe;
import com.cloudhandson.tossstock.market.UniverseMapper;
import com.cloudhandson.tossstock.toss.CandleCache;
import com.cloudhandson.tossstock.toss.PriceCache;
import com.cloudhandson.tossstock.toss.StockInfoCache;
import com.cloudhandson.tossstock.toss.dto.TossCandle;
import com.cloudhandson.tossstock.toss.dto.TossPrice;
import com.cloudhandson.tossstock.toss.dto.TossStock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * 워치리스트 → 거래량 내림차순 quote 목록. 부분 실패 허용.
 * 설계: docs/design/414-watchlist-page/fn-quote-assembly.md
 */
@Service
public class WatchlistQuoteService {

    private static final Logger log = LoggerFactory.getLogger(WatchlistQuoteService.class);
    private static final int MAX_ROWS = 50;

    private final WatchlistMapper mapper;
    private final PriceCache priceCache;
    private final StockInfoCache stockInfoCache;
    private final CandleCache candleCache;
    private final UniverseMapper universeMapper;

    public WatchlistQuoteService(WatchlistMapper mapper, PriceCache priceCache, StockInfoCache stockInfoCache,
                                 CandleCache candleCache, UniverseMapper universeMapper) {
        this.mapper = mapper;
        this.priceCache = priceCache;
        this.stockInfoCache = stockInfoCache;
        this.candleCache = candleCache;
        this.universeMapper = universeMapper;
    }

    public List<WatchlistQuote> assemble() {
        List<Watchlist> rows = mapper.findAll();
        if (rows.isEmpty()) {
            return List.of();
        }
        List<String> symbols = rows.stream().map(Watchlist::getSymbol).distinct().toList();

        Map<String, TossPrice> priceBySym = safe(() -> priceCache.get(symbols)).stream()
                .collect(Collectors.toMap(TossPrice::symbol, Function.identity(), (a, b) -> a));
        Map<String, TossStock> nameBySym = safe(() -> stockInfoCache.get(symbols)).stream()
                .collect(Collectors.toMap(TossStock::symbol, Function.identity(), (a, b) -> a));
        Map<String, String> sectorBySym = universeMapper.sectorsForSymbols(symbols).stream()
                .filter(u -> u.getSector() != null)
                .collect(Collectors.toMap(Universe::getSymbol, Universe::getSector, (a, b) -> a));

        List<WatchlistQuote> quotes = new ArrayList<>();
        for (Watchlist r : rows) {
            String name = nameBySym.containsKey(r.getSymbol()) ? nameBySym.get(r.getSymbol()).name() : null;
            String sector = sectorBySym.get(r.getSymbol());
            TossPrice price = priceBySym.get(r.getSymbol());
            try {
                List<TossCandle> candles = candleCache.get(r.getSymbol());
                quotes.add(toRow(r, price, name, sector, candles));
            } catch (RuntimeException e) {
                log.warn("quote 조립 부분 실패 symbol={}: {}", r.getSymbol(), e.getMessage());
                quotes.add(toRow(r, price, name, sector, List.of()).withStale());
            }
        }

        quotes.sort(Comparator.comparing(WatchlistQuote::volume,
                Comparator.nullsLast(Comparator.reverseOrder())));

        List<WatchlistQuote> ranked = new ArrayList<>();
        int rank = 1;
        for (WatchlistQuote q : quotes) {
            if (rank > MAX_ROWS) {
                break;
            }
            ranked.add(q.withRank(rank++));
        }
        return ranked;
    }

    /** 순수 함수: 한 종목 행 + 등락률 계산. 외부 호출 없음(테스트 대상). */
    WatchlistQuote toRow(Watchlist r, TossPrice price, String name, String sector, List<TossCandle> candles) {
        BigDecimal last = price == null ? null : toDecimal(price.lastPrice());
        Long volume = candles.isEmpty() ? null : toLong(candles.get(0).volume());
        BigDecimal prevClose = candles.size() >= 2 ? toDecimal(candles.get(1).closePrice()) : null;

        BigDecimal changeAmount = (last != null && prevClose != null) ? last.subtract(prevClose) : null;
        Double changeRate = null;
        if (changeAmount != null && prevClose != null && prevClose.signum() != 0) {
            changeRate = changeAmount.divide(prevClose, 6, RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(100))
                    .setScale(2, RoundingMode.HALF_UP)
                    .doubleValue();
        }
        String currency = price != null ? price.currency()
                : (!candles.isEmpty() ? candles.get(0).currency() : null);

        return new WatchlistQuote(r.getId(), r.getSymbol(), name == null ? "-" : name, sector,
                last, prevClose, changeAmount, changeRate, volume, currency, 0, false);
    }

    private static <T> List<T> safe(Supplier<List<T>> call) {
        try {
            return call.get();
        } catch (RuntimeException e) {
            log.warn("토스 일괄 조회 실패: {}", e.getMessage());
            return List.of();
        }
    }

    private static BigDecimal toDecimal(String s) {
        try {
            return s == null ? null : new BigDecimal(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Long toLong(String s) {
        try {
            return s == null ? null : new BigDecimal(s).longValue();
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
