package com.cloudhandson.tossstock.holding;

import com.cloudhandson.tossstock.market.DailyOhlcvMapper;
import com.cloudhandson.tossstock.market.UniverseMapper;
import com.cloudhandson.tossstock.toss.TossApiClient;
import com.cloudhandson.tossstock.toss.dto.TossPrice;
import com.cloudhandson.tossstock.toss.dto.TossStock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/** 거래원장 → 종목 단위 평균단가 집계 + 현재가/지표 보강. (#433) */
@Service
public class HoldingService {

    private static final Logger log = LoggerFactory.getLogger(HoldingService.class);

    private final HoldingMapper mapper;
    private final TossApiClient toss;
    private final UniverseMapper universeMapper;
    private final DailyOhlcvMapper dailyMapper;

    public HoldingService(HoldingMapper mapper, TossApiClient toss,
                          UniverseMapper universeMapper, DailyOhlcvMapper dailyMapper) {
        this.mapper = mapper;
        this.toss = toss;
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

        Map<String, BigDecimal> priceBySym = safe(() -> toss.getPrices(symbols)).stream()
                .filter(p -> p.lastPrice() != null)
                .collect(Collectors.toMap(TossPrice::symbol, p -> new BigDecimal(p.lastPrice()), (a, b) -> a));
        Map<String, String> nameBySym = safe(() -> toss.getStocks(symbols)).stream()
                .collect(Collectors.toMap(TossStock::symbol, TossStock::name, (a, b) -> a));

        LocalDateTime now = LocalDateTime.now();
        return bySym.entrySet().stream().map(e -> {
            String sym = e.getKey();
            List<Holding> trades = e.getValue();
            BigDecimal cur = priceBySym.get(sym);
            BigDecimal peak = null, trough = null;
            LocalDate firstBuyDate = trades.stream()
                    .filter(t -> !t.isSell()).map(Holding::getBuyAt).filter(Objects::nonNull)
                    .map(LocalDateTime::toLocalDate).min(Comparator.naturalOrder()).orElse(null);
            if (firstBuyDate != null) {
                Map<String, Object> r = dailyMapper.rangeSince(sym, firstBuyDate);
                peak = dec(r == null ? null : r.get("MAXHIGH"));
                trough = dec(r == null ? null : r.get("MINLOW"));
            }
            return PositionCalc.of(sym, nameBySym.get(sym), safeSector(sym), trades, cur, peak, trough, now);
        }).toList();
    }

    private String safeSector(String symbol) {
        try {
            return universeMapper.findSectorBySymbol(symbol);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static <T> List<T> safe(Supplier<List<T>> call) {
        try {
            return call.get();
        } catch (RuntimeException e) {
            log.warn("보유 보강 조회 실패: {}", e.getMessage());
            return List.of();
        }
    }

    private static BigDecimal dec(Object o) {
        return o instanceof BigDecimal b ? b : (o instanceof Number n ? BigDecimal.valueOf(n.doubleValue()) : null);
    }
}
