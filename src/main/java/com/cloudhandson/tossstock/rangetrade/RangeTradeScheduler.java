package com.cloudhandson.tossstock.rangetrade;

import com.cloudhandson.tossstock.autotrade.LiquidityChecker;
import com.cloudhandson.tossstock.market.DailyOhlcv;
import com.cloudhandson.tossstock.market.DailyOhlcvMapper;
import com.cloudhandson.tossstock.market.DailyRangeStats;
import com.cloudhandson.tossstock.toss.PriceCache;
import com.cloudhandson.tossstock.toss.dto.TossPrice;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 레인지 트랙 한 틱(1일 1회, 장마감 후)의 오케스트레이션 — 판정은 순수 클래스/게이트에 위임하고 여기선 호출만.
 * 설계: docs/design/818-range-trade-swing/README.md §5, §8
 */
@Service
public class RangeTradeScheduler {

    private static final Logger log = LoggerFactory.getLogger(RangeTradeScheduler.class);

    /** Oracle IN 리스트 상한(1000) 아래로 안전하게 자르는 청크 크기. */
    private static final int SYMBOL_CHUNK = 900;
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private final RangeTradeProperties props;
    private final RangeTradeStateMapper stateMapper;
    private final RangeTradePositionMapper positionMapper;
    private final DailyOhlcvMapper dailyMapper;
    private final PriceCache priceCache;
    private final BadNewsGate badNewsGate;
    private final EarningsCalendarGate earningsGate;
    private final RangeOrderExecutor orderExecutor;

    public RangeTradeScheduler(RangeTradeProperties props, RangeTradeStateMapper stateMapper,
                               RangeTradePositionMapper positionMapper, DailyOhlcvMapper dailyMapper,
                               PriceCache priceCache, BadNewsGate badNewsGate,
                               EarningsCalendarGate earningsGate, RangeOrderExecutor orderExecutor) {
        this.props = props;
        this.stateMapper = stateMapper;
        this.positionMapper = positionMapper;
        this.dailyMapper = dailyMapper;
        this.priceCache = priceCache;
        this.badNewsGate = badNewsGate;
        this.earningsGate = earningsGate;
        this.orderExecutor = orderExecutor;
    }

    @Scheduled(cron = "${range-trade.cron:0 0 16 * * MON-FRI}", zone = "Asia/Seoul")
    public void tick() {
        RangeTradeState state = stateMapper.find();
        if (state == null) {
            log.warn("range_trade_state 없음 — 스키마 초기화 확인 필요");
            return;
        }

        for (RangeTradePosition p : positionMapper.findHolding()) {
            try {
                processHolding(p);
            } catch (RuntimeException e) {
                // 한 종목 예외가 전체 틱을 죽이지 않게(README §9 — #808 실사례 재발 방지).
                log.warn("[레인지] 포지션 점검 실패(symbol={}): {}", p.getSymbol(), e.toString());
            }
        }

        if (state.isCircuitBreakerTripped()) {
            log.warn("[레인지] 서킷브레이커 트립 상태 — 신규 매수 스킵(매도는 위에서 처리됨)");
            return;
        }
        int holdingCount = positionMapper.countHolding();
        int openSlots = props.maxSymbols() - holdingCount;
        if (openSlots <= 0) {
            log.info("[레인지] 빈 슬롯 없음(보유 {}종목 / 슬롯 {}) — 신규 스캔 생략", holdingCount, props.maxSymbols());
            return;
        }
        scanCandidates(openSlots);
    }

    /** 보유 포지션 1건 점검 — 악재 우선, 그 다음 가격 기반 익절/손절(README §8-3). */
    private void processHolding(RangeTradePosition p) {
        BigDecimal current = livePrice(p.getSymbol());
        if (current == null) {
            log.warn("[레인지] 현재가 조회 실패(symbol={}) — 이번 틱 스킵", p.getSymbol());
            return;
        }
        if (badNewsGate.hasStrongBadNews(p.getSymbol())) {
            log.info("[레인지] 악재(S1/S2) 발생 — 즉시 매도: symbol={}", p.getSymbol());
            orderExecutor.sell(p, RangeExitReason.BAD_NEWS, current);
            return;
        }
        RangeTradeSignal.Signal signal = RangeTradeSignal.decide(current, p.getRangeLowAtEntry(),
                p.getRangeHighAtEntry(), true, props);
        switch (signal) {
            case PROFIT_TAKE -> orderExecutor.sell(p, RangeExitReason.PROFIT_TAKE, current);
            case RANGE_BREAKDOWN -> orderExecutor.sell(p, RangeExitReason.RANGE_BREAKDOWN, current);
            default -> log.info("[레인지] 보유 유지: symbol={}, 현재가={}, 밴드={}~{}", p.getSymbol(), current,
                    p.getRangeLowAtEntry(), p.getRangeHighAtEntry());
        }
    }

    /** 유니버스 전체 스캔 → 1차 스크리닝 → 실제 일봉으로 최종 판정 → 매수(README §8-4). */
    private void scanCandidates(int openSlots) {
        LocalDate fromDate = LocalDate.now().minusDays(props.windowDays() * 2L); // 거래일 windowDays+1개를 담을 여유
        List<DailyRangeStats> stats = dailyMapper.rangeStatsBatch(fromDate, props.requiredBars());
        List<DailyRangeStats> screened = stats.stream().filter(this::coarsePass).toList();
        log.info("[레인지] 스캔: 통계 {}종목 → 1차 스크리닝 {}종목(빈 슬롯 {})", stats.size(), screened.size(), openSlots);
        if (screened.isEmpty()) {
            return;
        }

        Set<String> holdingSymbols = new HashSet<>();
        positionMapper.findHolding().forEach(p -> holdingSymbols.add(p.getSymbol()));
        Map<String, List<DailyOhlcv>> barsBySymbol =
                loadBars(screened.stream().map(DailyRangeStats::getSymbol).toList(), fromDate);

        int filled = 0;
        for (DailyRangeStats s : screened) {
            if (filled >= openSlots) {
                break;
            }
            try {
                if (tryBuy(s.getSymbol(), barsBySymbol.get(s.getSymbol()), holdingSymbols)) {
                    filled++;
                }
            } catch (RuntimeException e) {
                log.warn("[레인지] 후보 점검 실패(symbol={}): {}", s.getSymbol(), e.toString());
            }
        }
    }

    /**
     * 성능용 1차 스크리닝(배치 통계 기반, 유니버스 3700+ → 수백 종목). 최종 판정은 아래 {@link #tryBuy}가
     * 실제 일봉으로 RangeBoundChecker/LiquidityChecker 를 돌려서 한다 — 두 경로가 어긋나면 후보가
     * 누락될 뿐(false negative) 잘못된 매수로는 이어지지 않는다(fail-closed 방향).
     */
    private boolean coarsePass(DailyRangeStats s) {
        if (s.getMaxHigh() == null || s.getMinLow() == null || s.getLastClose() == null
                || s.getFirstHalfAvgClose() == null || s.getSecondHalfAvgClose() == null) {
            return false;
        }
        double widthPct = spreadPct(s.getMaxHigh(), s.getMinLow());
        if (widthPct < props.minWidthPct() || widthPct > props.maxWidthPct()) {
            return false;
        }
        if (Math.abs(spreadPct(s.getSecondHalfAvgClose(), s.getFirstHalfAvgClose())) > props.maxTrendDriftPct()) {
            return false;
        }
        return s.getLastClose().compareTo(RangeTradeSignal.entryCeiling(s.getMinLow(), props)) <= 0;
    }

    /** 후보 1종목 최종 판정 + 매수. 매수했으면 true. */
    private boolean tryBuy(String symbol, List<DailyOhlcv> allBars, Set<String> holdingSymbols) {
        if (holdingSymbols.contains(symbol)) {
            return false; // 이미 보유 중
        }
        List<DailyOhlcv> window = recentWindow(allBars, props.requiredBars());
        if (window.size() < props.requiredBars()) {
            log.debug("[레인지] 일봉 부족으로 스킵: symbol={}", symbol);
            return false;
        }
        if (!LiquidityChecker.isLiquid(window, props.minAvgTradingValue())) {
            log.debug("[레인지] 유동성 미달로 스킵: symbol={}", symbol);
            return false;
        }
        RangeBoundChecker.Result band = RangeBoundChecker.evaluate(window, props);
        if (!band.isRangeBound()) {
            log.debug("[레인지] 박스권 아님으로 스킵: symbol={}", symbol);
            return false;
        }
        // 장마감 후 1일 1회 배치라 "최신 종가 = 현재가". 밴드와 같은 일봉 스냅샷에서 가격을 뽑아
        // 내부 일관성을 유지하고, 후보 수백 종목에 시세 API를 때려 #808 실거래 엔진의 레이트리밋을
        // 건드리는 일도 피한다(보유 포지션은 종목 수가 적어 실시간 시세를 쓴다).
        BigDecimal price = window.get(window.size() - 1).getCloseP();
        if (RangeTradeSignal.decide(price, band.low(), band.high(), false, props) != RangeTradeSignal.Signal.BUY) {
            log.debug("[레인지] 진입구간 아님으로 스킵: symbol={}, 종가={}, 밴드={}~{}",
                    symbol, price, band.low(), band.high());
            return false;
        }
        if (badNewsGate.hasStrongBadNews(symbol)) {
            log.info("[레인지] 악재(S1/S2)로 신규 매수 차단: symbol={}", symbol);
            return false;
        }
        if (earningsGate.hasUpcomingEarnings(symbol, props.holdingHorizonDays())) {
            return false; // 사유는 게이트 내부에서 로그
        }
        return orderExecutor.buy(symbol, props.perSymbolBudget(), price, band.low(), band.high());
    }

    /** 종목별 일봉을 청크로 일괄 조회(N+1 방지). */
    private Map<String, List<DailyOhlcv>> loadBars(List<String> symbols, LocalDate fromDate) {
        Map<String, List<DailyOhlcv>> out = new HashMap<>();
        for (int i = 0; i < symbols.size(); i += SYMBOL_CHUNK) {
            List<String> chunk = symbols.subList(i, Math.min(i + SYMBOL_CHUNK, symbols.size()));
            for (DailyOhlcv bar : dailyMapper.recentForSymbols(chunk, fromDate)) {
                out.computeIfAbsent(bar.getSymbol(), k -> new ArrayList<>()).add(bar);
            }
        }
        return out;
    }

    /** 날짜순 정렬 후 최근 n개만(배치 통계와 동일한 윈도우를 쓰도록 맞춤). */
    private static List<DailyOhlcv> recentWindow(List<DailyOhlcv> bars, int n) {
        if (bars == null || bars.isEmpty()) {
            return List.of();
        }
        List<DailyOhlcv> sorted = new ArrayList<>(bars);
        sorted.sort(Comparator.comparing(DailyOhlcv::getTradeDate));
        return sorted.size() <= n ? sorted : sorted.subList(sorted.size() - n, sorted.size());
    }

    private static double spreadPct(BigDecimal a, BigDecimal b) {
        BigDecimal mid = a.add(b).divide(BigDecimal.valueOf(2), MathContext.DECIMAL64);
        if (mid.signum() == 0) {
            return 0.0;
        }
        return a.subtract(b).divide(mid, MathContext.DECIMAL64).multiply(HUNDRED).doubleValue();
    }

    private BigDecimal livePrice(String symbol) {
        List<TossPrice> prices = priceCache.get(List.of(symbol));
        if (prices.isEmpty() || prices.get(0).lastPrice() == null) {
            return null;
        }
        return new BigDecimal(prices.get(0).lastPrice());
    }
}
