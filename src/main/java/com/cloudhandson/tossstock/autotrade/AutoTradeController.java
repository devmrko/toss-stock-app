package com.cloudhandson.tossstock.autotrade;

import com.cloudhandson.tossstock.toss.PriceCache;
import com.cloudhandson.tossstock.toss.dto.TossPrice;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/** 운영 확인용 얇은 어댑터. 설계: docs/design/808-auto-trade-engine/README.md §3 인수조건,
 * docs/design/848-autotrade-dashboard/README.md(현재가·최근로그 추가). */
@RestController
@RequestMapping("/api/autotrade")
public class AutoTradeController {

    private static final int RECENT_LOG_LIMIT = 20;

    private final AutoTradeScheduler scheduler;
    private final CandidateDiscoveryService discoveryService;
    private final AutoTradeStateMapper stateMapper;
    private final AutoTradePositionMapper positionMapper;
    private final AutoTradeCandidateMapper candidateMapper;
    private final AutoTradeOrderLogMapper orderLogMapper;
    private final PriceCache priceCache;

    public AutoTradeController(AutoTradeScheduler scheduler, CandidateDiscoveryService discoveryService,
                                AutoTradeStateMapper stateMapper, AutoTradePositionMapper positionMapper,
                                AutoTradeCandidateMapper candidateMapper, AutoTradeOrderLogMapper orderLogMapper,
                                PriceCache priceCache) {
        this.scheduler = scheduler;
        this.discoveryService = discoveryService;
        this.stateMapper = stateMapper;
        this.positionMapper = positionMapper;
        this.candidateMapper = candidateMapper;
        this.orderLogMapper = orderLogMapper;
        this.priceCache = priceCache;
    }

    /** 스케줄러 강제 1회 실행(드라이런 여부는 auto_trade_state/설정을 그대로 따름 — 이 호출 자체가 안전장치를 우회하지 않음). */
    @PostMapping("/tick")
    public ResponseEntity<Map<String, Object>> tick() {
        scheduler.tick();
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(status());
    }

    /** 후보 자동등록/자동해제 강제 1회 실행(운영 확인용). */
    @PostMapping("/discover")
    public ResponseEntity<Map<String, Object>> discover() {
        discoveryService.refresh();
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(status());
    }

    @GetMapping("/status")
    public Map<String, Object> status() {
        AutoTradeState state = stateMapper.find();
        List<AutoTradePosition> holdings = positionMapper.findHolding();
        List<AutoTradeCandidate> candidates = candidateMapper.findActive();
        Map<String, Object> result = new java.util.HashMap<>();
        result.put("dryRun", state == null || state.isDryRun());
        result.put("circuitBreakerTripped", state != null && state.isCircuitBreakerTripped());
        result.put("totalBudget", state == null ? null : state.getTotalBudget());
        result.put("holdingCount", holdings.size());
        result.put("holdings", holdings);
        result.put("activeCandidates", candidates);
        result.put("holdingsView", holdingsView(holdings));
        result.put("recentLogs", orderLogMapper.findRecent(RECENT_LOG_LIMIT));
        return result;
    }

    /** 보유종목을 현재가와 짝지어 뷰로 변환(#848) — 시세조회 부분실패는 currentPrice=null로 흡수. */
    private List<HoldingPnlView> holdingsView(List<AutoTradePosition> holdings) {
        if (holdings.isEmpty()) {
            return List.of();
        }
        List<String> symbols = holdings.stream().map(AutoTradePosition::getSymbol).distinct().toList();
        Map<String, BigDecimal> priceBySymbol = new java.util.HashMap<>();
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
                .map(h -> HoldingPnlView.of(h, priceBySymbol.get(h.getSymbol())))
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
