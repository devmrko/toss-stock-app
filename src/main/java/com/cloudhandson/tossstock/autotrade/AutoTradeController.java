package com.cloudhandson.tossstock.autotrade;

import com.cloudhandson.tossstock.toss.PriceCache;
import com.cloudhandson.tossstock.toss.StockInfoCache;
import com.cloudhandson.tossstock.toss.dto.TossPrice;
import com.cloudhandson.tossstock.toss.dto.TossStock;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 운영 확인용 얇은 어댑터. 설계: docs/design/808-auto-trade-engine/README.md §3 인수조건,
 * docs/design/848-autotrade-dashboard/README.md(현재가·최근로그),
 * docs/design/849-dashboard-detail-and-summary/README.md(매수이유·요약·페이지네이션). */
@RestController
@RequestMapping("/api/autotrade")
public class AutoTradeController {

    private static final int DEFAULT_PAGE_SIZE = 20;

    private final AutoTradeScheduler scheduler;
    private final CandidateDiscoveryService discoveryService;
    private final AutoTradeStateMapper stateMapper;
    private final AutoTradePositionMapper positionMapper;
    private final AutoTradeCandidateMapper candidateMapper;
    private final AutoTradeOrderLogMapper orderLogMapper;
    private final PriceCache priceCache;
    private final StockInfoCache stockInfoCache;

    public AutoTradeController(AutoTradeScheduler scheduler, CandidateDiscoveryService discoveryService,
                                AutoTradeStateMapper stateMapper, AutoTradePositionMapper positionMapper,
                                AutoTradeCandidateMapper candidateMapper, AutoTradeOrderLogMapper orderLogMapper,
                                PriceCache priceCache, StockInfoCache stockInfoCache) {
        this.scheduler = scheduler;
        this.discoveryService = discoveryService;
        this.stateMapper = stateMapper;
        this.positionMapper = positionMapper;
        this.candidateMapper = candidateMapper;
        this.orderLogMapper = orderLogMapper;
        this.priceCache = priceCache;
        this.stockInfoCache = stockInfoCache;
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

        List<String> symbols = holdings.stream().map(AutoTradePosition::getSymbol).distinct().toList();
        Map<String, String> nameBySymbol = new HashMap<>();
        for (TossStock s : safeStockInfo(symbols)) {
            nameBySymbol.put(s.symbol(), s.name());
        }

        Map<String, Object> result = new HashMap<>();
        result.put("dryRun", state == null || state.isDryRun());
        result.put("circuitBreakerTripped", state != null && state.isCircuitBreakerTripped());
        result.put("totalBudget", state == null ? null : state.getTotalBudget());
        result.put("holdingCount", holdings.size());
        result.put("holdings", holdings);
        result.put("activeCandidates", candidates);
        result.put("holdingsView", holdingsView(holdings, nameBySymbol));
        result.put("symbolNames", nameBySymbol);
        result.put("summary", summary());
        return result;
    }

    /**
     * 총 매매건수/승패/누적손익/누적수수료(#849, 2026-10-08 정정) — EXITED 포지션·성공
     * 주문로그 기준(드라이런 포함). {@code realizedPnl}은 가격차만(매수가-매도가), 토스 앱이
     * 보여주는 실제 손익과는 다르다(수수료/세금 미반영) — {@code netRealizedPnl}이 거기서
     * 누적 수수료+세금을 뺀 값. #847 배포 이전 거래는 fee 컬럼이 비어있어(0으로 집계) 이
     * 보정이 아직 전부 반영되지 않음 — 신규 거래부터 정확해짐.
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
        List<AutoTradeOrderLog> content = orderLogMapper.findPage(safePage * safeSize, safeSize);
        List<String> symbols = content.stream().map(AutoTradeOrderLog::getSymbol).distinct().toList();
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

    /** 보유종목을 현재가·종목명·매수이유와 짝지어 뷰로 변환(#848/#849) — 시세조회 부분실패는 currentPrice=null로 흡수. */
    private List<HoldingPnlView> holdingsView(List<AutoTradePosition> holdings, Map<String, String> nameBySymbol) {
        if (holdings.isEmpty()) {
            return List.of();
        }
        List<String> symbols = holdings.stream().map(AutoTradePosition::getSymbol).distinct().toList();
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
                .map(h -> HoldingPnlView.of(h, priceBySymbol.get(h.getSymbol()), nameBySymbol.get(h.getSymbol()),
                        buyReasonOf(h.getSymbol())))
                .toList();
    }

    /**
     * 현재 보유 중인 심볼은 중복매수가 금지돼 있어(§9 불변식) 그 심볼의 가장 최근 성공 BUY 로그가
     * 항상 이 포지션 자신의 매수 기록이다 — 포지션 테이블에 rationale을 별도 저장하지 않고도
     * 안전하게 역추적할 수 있다(#849).
     */
    private String buyReasonOf(String symbol) {
        try {
            AutoTradeOrderLog log = orderLogMapper.findLastBuySuccess(symbol);
            return log == null ? null : log.getMessage();
        } catch (RuntimeException e) {
            return null;
        }
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
