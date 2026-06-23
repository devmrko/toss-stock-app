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
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/** 보유 목록 + 현재가/지표 보강. */
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

    public List<HoldingView> list() {
        List<Holding> rows = mapper.findAll();
        if (rows.isEmpty()) {
            return List.of();
        }
        List<String> symbols = rows.stream().map(Holding::getSymbol).distinct().toList();

        Map<String, BigDecimal> priceBySym = safe(() -> toss.getPrices(symbols)).stream()
                .filter(p -> p.lastPrice() != null)
                .collect(Collectors.toMap(TossPrice::symbol, p -> new BigDecimal(p.lastPrice()), (a, b) -> a));
        Map<String, String> nameBySym = safe(() -> toss.getStocks(symbols)).stream()
                .collect(Collectors.toMap(TossStock::symbol, TossStock::name, (a, b) -> a));

        LocalDateTime now = LocalDateTime.now();
        return rows.stream().map(h -> {
            String sector = safeSector(h.getSymbol());
            BigDecimal cur = priceBySym.get(h.getSymbol());
            BigDecimal peak = null, trough = null;
            if (h.getBuyAt() != null) {
                Map<String, Object> r = dailyMapper.rangeSince(h.getSymbol(), h.getBuyAt().toLocalDate());
                peak = dec(r == null ? null : r.get("MAXHIGH"));
                trough = dec(r == null ? null : r.get("MINLOW"));
            }
            return HoldingCalc.of(h, nameBySym.get(h.getSymbol()), sector, cur, peak, trough, now);
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
