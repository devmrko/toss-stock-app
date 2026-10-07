package com.cloudhandson.tossstock.rangetrade;

import com.cloudhandson.tossstock.toss.PriceCache;
import com.cloudhandson.tossstock.toss.dto.TossPrice;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 운영 확인용 얇은 어댑터(레인지 엔진, #808 {@code AutoTradeController}와 동일 패턴).
 * 설계: docs/design/848-autotrade-dashboard/README.md */
@RestController
@RequestMapping("/api/rangetrade")
public class RangeTradeController {

    private static final int RECENT_LOG_LIMIT = 20;

    private final RangeTradeStateMapper stateMapper;
    private final RangeTradePositionMapper positionMapper;
    private final RangeTradeOrderLogMapper orderLogMapper;
    private final PriceCache priceCache;

    public RangeTradeController(RangeTradeStateMapper stateMapper, RangeTradePositionMapper positionMapper,
                                 RangeTradeOrderLogMapper orderLogMapper, PriceCache priceCache) {
        this.stateMapper = stateMapper;
        this.positionMapper = positionMapper;
        this.orderLogMapper = orderLogMapper;
        this.priceCache = priceCache;
    }

    @GetMapping("/status")
    public Map<String, Object> status() {
        RangeTradeState state = stateMapper.find();
        List<RangeTradePosition> holdings = positionMapper.findHolding();
        Map<String, Object> result = new HashMap<>();
        result.put("dryRun", state == null || state.isDryRun());
        result.put("circuitBreakerTripped", state != null && state.isCircuitBreakerTripped());
        result.put("totalBudget", state == null ? null : state.getTotalBudget());
        result.put("holdingCount", holdings.size());
        result.put("holdingsView", holdingsView(holdings));
        result.put("recentLogs", orderLogMapper.findRecent(RECENT_LOG_LIMIT));
        return result;
    }

    /** 보유종목을 현재가와 짝지어 뷰로 변환(#848) — 시세조회 부분실패는 currentPrice=null로 흡수. */
    private List<RangeHoldingPnlView> holdingsView(List<RangeTradePosition> holdings) {
        if (holdings.isEmpty()) {
            return List.of();
        }
        List<String> symbols = holdings.stream().map(RangeTradePosition::getSymbol).distinct().toList();
        Map<String, BigDecimal> priceBySymbol = new HashMap<>();
        for (TossPrice p : safePrices(symbols)) {
            if (p.lastPrice() != null) {
                try {
                    priceBySymbol.put(p.symbol(), new BigDecimal(p.lastPrice()));
                } catch (NumberFormatException ignored) {
                    // 파싱 실패는 시세 없음과 동일하게 취급(currentPrice=null)
                }
            }
        }
        return holdings.stream()
                .map(h -> RangeHoldingPnlView.of(h, priceBySymbol.get(h.getSymbol())))
                .toList();
    }

    private List<TossPrice> safePrices(List<String> symbols) {
        try {
            return priceCache.get(symbols);
        } catch (RuntimeException e) {
            return List.of();
        }
    }
}
