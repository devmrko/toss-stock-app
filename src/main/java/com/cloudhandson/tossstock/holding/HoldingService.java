package com.cloudhandson.tossstock.holding;

import com.cloudhandson.tossstock.market.DailyOhlcv;
import com.cloudhandson.tossstock.market.DailyOhlcvMapper;
import com.cloudhandson.tossstock.market.Universe;
import com.cloudhandson.tossstock.market.UniverseMapper;
import com.cloudhandson.tossstock.toss.PriceCache;
import com.cloudhandson.tossstock.toss.StockInfoCache;
import com.cloudhandson.tossstock.toss.dto.TossPrice;
import com.cloudhandson.tossstock.toss.dto.TossStock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/** 거래원장 → 종목 단위 평균단가 집계 + 현재가/지표 보강. (#433) 시세/섹터 일괄 조회로 N+1 제거(#436). */
@Service
public class HoldingService {

    private static final Logger log = LoggerFactory.getLogger(HoldingService.class);

    private final HoldingMapper mapper;
    private final PriceCache priceCache;
    private final StockInfoCache stockInfoCache;
    private final UniverseMapper universeMapper;
    private final DailyOhlcvMapper dailyMapper;

    public HoldingService(HoldingMapper mapper, PriceCache priceCache, StockInfoCache stockInfoCache,
                          UniverseMapper universeMapper, DailyOhlcvMapper dailyMapper) {
        this.mapper = mapper;
        this.priceCache = priceCache;
        this.stockInfoCache = stockInfoCache;
        this.universeMapper = universeMapper;
        this.dailyMapper = dailyMapper;
    }

    public List<PositionView> list() {
        List<Holding> rows = mapper.findAll();
        if (rows.isEmpty()) {
            return List.of();
        }
        // 종목별 거래 묶음(원장 정렬 = buy_at desc 유지)
        Map<String, List<Holding>> bySym = rows.stream()
                .collect(Collectors.groupingBy(Holding::getSymbol, LinkedHashMap::new, Collectors.toList()));
        List<String> symbols = List.copyOf(bySym.keySet());

        // 일괄 조회(각 1콜/쿼리) — 종목별 반복 호출 제거
        Map<String, BigDecimal> priceBySym = safe(() -> priceCache.get(symbols)).stream()
                .filter(p -> p.lastPrice() != null)
                .collect(Collectors.toMap(TossPrice::symbol, p -> new BigDecimal(p.lastPrice()), (a, b) -> a));
        Map<String, String> nameBySym = safe(() -> stockInfoCache.get(symbols)).stream()
                .collect(Collectors.toMap(TossStock::symbol, TossStock::name, (a, b) -> a));
        Map<String, String> sectorBySym = universeMapper.sectorsForSymbols(symbols).stream()
                .filter(u -> u.getSector() != null)
                .collect(Collectors.toMap(Universe::getSymbol, Universe::getSector, (a, b) -> a));

        // 1·7일 등락용 최근 일봉(작은 범위, 1쿼리)
        Map<String, List<DailyOhlcv>> barsBySym = dailyMapper.recentForSymbols(symbols, LocalDate.now().minusDays(20))
                .stream().collect(Collectors.groupingBy(DailyOhlcv::getSymbol, LinkedHashMap::new, Collectors.toList()));

        // 종목별 최초매수일 + (그 이후) 고점/저점을 집계 1쿼리로(원시 일봉 대량 전송 회피)
        Map<String, LocalDate> firstBuyBySym = new LinkedHashMap<>();
        bySym.forEach((sym, trades) -> trades.stream()
                .filter(t -> !t.isSell()).map(Holding::getBuyAt).filter(Objects::nonNull)
                .map(LocalDateTime::toLocalDate).min(Comparator.naturalOrder())
                .ifPresent(d -> firstBuyBySym.put(sym, d)));
        Map<String, BigDecimal[]> ptBySym = peakTroughBySymbol(firstBuyBySym);

        LocalDateTime now = LocalDateTime.now();
        return bySym.entrySet().stream().map(e -> {
            String sym = e.getKey();
            List<Holding> trades = e.getValue();
            BigDecimal cur = priceBySym.get(sym);
            List<DailyOhlcv> bars = barsBySym.getOrDefault(sym, List.of());
            Double c1 = changePct(bars, 1, true);
            Double c7 = changePct(bars, 7, false);
            BigDecimal[] pt = ptBySym.getOrDefault(sym, new BigDecimal[]{null, null});
            return PositionCalc.of(sym, nameBySym.get(sym), sectorBySym.get(sym), trades, cur, c1, c7,
                    pt[0], pt[1], now);
        }).toList();
    }

    private static <T> List<T> safe(Supplier<List<T>> call) {
        try {
            return call.get();
        } catch (RuntimeException e) {
            log.warn("보유 보강 조회 실패: {}", e.getMessage());
            return List.of();
        }
    }

    /** 종목별 (최초매수일 이후) 고점/저점을 집계 1쿼리로. {symbol→[peak,trough]}. */
    private Map<String, BigDecimal[]> peakTroughBySymbol(Map<String, LocalDate> firstBuyBySym) {
        if (firstBuyBySym.isEmpty()) {
            return Map.of();
        }
        List<Map<String, Object>> pairs = firstBuyBySym.entrySet().stream()
                .map(en -> Map.<String, Object>of("symbol", en.getKey(), "fromDate", en.getValue()))
                .toList();
        Map<String, BigDecimal[]> out = new LinkedHashMap<>();
        for (Map<String, Object> r : dailyMapper.peakTroughBatch(pairs)) {
            out.put((String) r.get("SYMBOL"), new BigDecimal[]{dec(r.get("MAXHIGH")), dec(r.get("MINLOW"))});
        }
        return out;
    }

    private static BigDecimal dec(Object o) {
        return o instanceof BigDecimal b ? b : (o instanceof Number n ? BigDecimal.valueOf(n.doubleValue()) : null);
    }

    /** 최근 일봉 종가로 등락%. byBar=true: 직전 거래일 대비(1일), false: n일 전(달력) 종가 대비. */
    private static Double changePct(List<DailyOhlcv> bars, int n, boolean byBar) {
        if (bars.size() < 2) {
            return null;
        }
        DailyOhlcv lastBar = bars.get(bars.size() - 1);
        BigDecimal last = lastBar.getCloseP();
        BigDecimal baseP = null;
        if (byBar) {
            baseP = bars.get(bars.size() - 2).getCloseP();
        } else {
            LocalDate target = lastBar.getTradeDate().minusDays(n);
            for (int i = bars.size() - 2; i >= 0; i--) {
                if (!bars.get(i).getTradeDate().isAfter(target)) {
                    baseP = bars.get(i).getCloseP();
                    break;
                }
            }
            if (baseP == null) {
                baseP = bars.get(0).getCloseP();
            }
        }
        if (last == null || baseP == null || baseP.signum() == 0) {
            return null;
        }
        return last.subtract(baseP).divide(baseP, 6, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100)).setScale(2, RoundingMode.HALF_UP).doubleValue();
    }
}
