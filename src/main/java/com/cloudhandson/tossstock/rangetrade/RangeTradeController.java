package com.cloudhandson.tossstock.rangetrade;

import com.cloudhandson.tossstock.toss.PriceCache;
import com.cloudhandson.tossstock.toss.StockInfoCache;
import com.cloudhandson.tossstock.toss.dto.TossPrice;
import com.cloudhandson.tossstock.toss.dto.TossStock;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 운영 확인용 얇은 어댑터(레인지 엔진, #808 {@code AutoTradeController}와 동일 패턴).
 * 설계: docs/design/848-autotrade-dashboard/README.md, docs/design/849-dashboard-detail-and-summary/README.md */
@RestController
@RequestMapping("/api/rangetrade")
public class RangeTradeController {

    private static final int DEFAULT_PAGE_SIZE = 20;

    private final RangeTradeStateMapper stateMapper;
    private final RangeTradePositionMapper positionMapper;
    private final RangeTradeOrderLogMapper orderLogMapper;
    private final PriceCache priceCache;
    private final StockInfoCache stockInfoCache;

    public RangeTradeController(RangeTradeStateMapper stateMapper, RangeTradePositionMapper positionMapper,
                                 RangeTradeOrderLogMapper orderLogMapper, PriceCache priceCache,
                                 StockInfoCache stockInfoCache) {
        this.stateMapper = stateMapper;
        this.positionMapper = positionMapper;
        this.orderLogMapper = orderLogMapper;
        this.priceCache = priceCache;
        this.stockInfoCache = stockInfoCache;
    }

    @GetMapping("/status")
    public Map<String, Object> status() {
        RangeTradeState state = stateMapper.find();
        List<RangeTradePosition> holdings = positionMapper.findHolding();

        List<String> symbols = holdings.stream().map(RangeTradePosition::getSymbol).distinct().toList();
        Map<String, String> nameBySymbol = new HashMap<>();
        for (TossStock s : safeStockInfo(symbols)) {
            nameBySymbol.put(s.symbol(), s.name());
        }

        Map<String, Object> result = new HashMap<>();
        result.put("dryRun", state == null || state.isDryRun());
        result.put("circuitBreakerTripped", state != null && state.isCircuitBreakerTripped());
        result.put("totalBudget", state == null ? null : state.getTotalBudget());
        result.put("holdingCount", holdings.size());
        result.put("holdingsView", holdingsView(holdings, nameBySymbol));
        result.put("symbolNames", nameBySymbol);
        result.put("summary", summary());
        return result;
    }

    /**
     * 총 매매건수/승패/누적손익/누적수수료(#849, 2026-10-08 정정) — EXITED 포지션·성공
     * 주문로그 기준(드라이런 포함). {@code realizedPnl}은 가격차만, {@code netRealizedPnl}이
     * 거기서 누적 수수료+세금을 뺀 값(#808 AutoTradeController와 동일 정정).
     */
    private Map<String, Object> summary() {
        BigDecimal priceOnlyPnl = positionMapper.realizedPnlTotal();
        BigDecimal totalFees = orderLogMapper.totalFees();
        Map<String, Object> s = new HashMap<>();
        s.put("totalTrades", positionMapper.countExited());
        s.put("wins", positionMapper.countWin());
        s.put("losses", positionMapper.countLoss());
        s.put("realizedPnl", priceOnlyPnl);
        s.put("totalFees", totalFees);
        s.put("netRealizedPnl", priceOnlyPnl.subtract(totalFees));
        return s;
    }

    /** 주문로그 전체 이력 페이지(시간순 최신→과거, #849) — 고정 20건이던 /status.recentLogs 대체. */
    @GetMapping("/orderlog")
    public Map<String, Object> orderlog(@RequestParam(defaultValue = "0") int page,
                                         @RequestParam(defaultValue = "" + DEFAULT_PAGE_SIZE) int size) {
        int safeSize = size <= 0 ? DEFAULT_PAGE_SIZE : size;
        int safePage = Math.max(page, 0);
        List<RangeTradeOrderLog> content = orderLogMapper.findPage(safePage * safeSize, safeSize);
        List<String> symbols = content.stream().map(RangeTradeOrderLog::getSymbol).distinct().toList();
        Map<String, String> nameBySymbol = new HashMap<>();
        for (TossStock s : safeStockInfo(symbols)) {
            nameBySymbol.put(s.symbol(), s.name());
        }

        Map<String, Object> result = new HashMap<>();
        result.put("content", content);
        result.put("page", safePage);
        result.put("size", safeSize);
        result.put("totalElements", orderLogMapper.countAll());
        result.put("symbolNames", nameBySymbol);
        return result;
    }

    /** 보유종목을 현재가·종목명과 짝지어 뷰로 변환(#848) — 시세조회 부분실패는 currentPrice=null로 흡수. */
    private List<RangeHoldingPnlView> holdingsView(List<RangeTradePosition> holdings,
                                                     Map<String, String> nameBySymbol) {
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
                .map(h -> RangeHoldingPnlView.of(h, priceBySymbol.get(h.getSymbol()), nameBySymbol.get(h.getSymbol())))
                .toList();
    }

    private List<TossPrice> safePrices(List<String> symbols) {
        try {
            return priceCache.get(symbols);
        } catch (RuntimeException e) {
            return List.of();
        }
    }

    private List<TossStock> safeStockInfo(List<String> symbols) {
        try {
            return stockInfoCache.get(symbols);
        } catch (RuntimeException e) {
            return List.of();
        }
    }
}
