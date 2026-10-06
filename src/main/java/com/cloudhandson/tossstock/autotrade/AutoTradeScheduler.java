package com.cloudhandson.tossstock.autotrade;

import com.cloudhandson.tossstock.briefing.DiscordClient;
import com.cloudhandson.tossstock.market.DailyOhlcv;
import com.cloudhandson.tossstock.market.DailyOhlcvMapper;
import com.cloudhandson.tossstock.market.UniverseMapper;
import com.cloudhandson.tossstock.news.NewsSignals;
import com.cloudhandson.tossstock.news.StockNews;
import com.cloudhandson.tossstock.news.StockNewsMapper;
import com.cloudhandson.tossstock.toss.PriceCache;
import com.cloudhandson.tossstock.toss.dto.TossPrice;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 자동매매 한 틱의 오케스트레이션 — 로직은 각 순수/전담 클래스에 위임하고 여기선 호출만.
 * 설계: docs/design/808-auto-trade-engine/README.md §7, §8
 */
@Service
public class AutoTradeScheduler {

    private static final Logger log = LoggerFactory.getLogger(AutoTradeScheduler.class);

    /** 결정근거 message(VARCHAR2(500))에 담을 뉴스 제목 길이 상한(#832). */
    private static final int MAX_NEWS_TITLE_LEN = 45;

    private final AutoTradeProperties props;
    private final AutoTradeStateMapper stateMapper;
    private final AutoTradePositionMapper positionMapper;
    private final AutoTradeCandidateMapper candidateMapper;
    private final DailyOhlcvMapper dailyMapper;
    private final StockNewsMapper newsMapper;
    private final PriceCache priceCache;
    private final NewsFadeDetector newsFadeDetector;
    private final ValuationClient valuationClient;
    private final CapitalReturnCatalystDetector capitalReturnCatalystDetector;
    private final UniverseMapper universeMapper;
    private final CandidateDiscoveryService candidateDiscovery;
    private final OrderExecutor orderExecutor;
    private final DiscordClient discord;

    public AutoTradeScheduler(AutoTradeProperties props, AutoTradeStateMapper stateMapper,
                               AutoTradePositionMapper positionMapper, AutoTradeCandidateMapper candidateMapper,
                               DailyOhlcvMapper dailyMapper, StockNewsMapper newsMapper, PriceCache priceCache,
                               NewsFadeDetector newsFadeDetector, ValuationClient valuationClient,
                               CapitalReturnCatalystDetector capitalReturnCatalystDetector,
                               UniverseMapper universeMapper, CandidateDiscoveryService candidateDiscovery,
                               OrderExecutor orderExecutor, DiscordClient discord) {
        this.props = props;
        this.stateMapper = stateMapper;
        this.positionMapper = positionMapper;
        this.candidateMapper = candidateMapper;
        this.dailyMapper = dailyMapper;
        this.newsMapper = newsMapper;
        this.priceCache = priceCache;
        this.newsFadeDetector = newsFadeDetector;
        this.valuationClient = valuationClient;
        this.capitalReturnCatalystDetector = capitalReturnCatalystDetector;
        this.universeMapper = universeMapper;
        this.candidateDiscovery = candidateDiscovery;
        this.orderExecutor = orderExecutor;
        this.discord = discord;
    }

    @Scheduled(cron = "${auto-trade.cron:0 * * * * *}", zone = "Asia/Seoul")
    public void tick() {
        AutoTradeState state = stateMapper.find();
        if (state == null) {
            log.warn("auto_trade_state 없음 — 스키마 초기화 확인 필요");
            return;
        }

        List<AutoTradePosition> holdings = positionMapper.findHolding();
        for (AutoTradePosition p : holdings) {
            try {
                processHolding(p);
            } catch (RuntimeException e) {
                log.warn("포지션 점검 실패(symbol={}): {}", p.getSymbol(), e.toString());
            }
        }

        boolean tripped = checkCircuitBreaker(state);
        if (tripped || state.isCircuitBreakerTripped()) {
            return; // 서킷브레이커 상태 — 신규 매수 전부 스킵(매도는 이미 위에서 처리됨)
        }

        int openSlots = props.maxSymbols() - positionMapper.countHolding();
        if (openSlots <= 0) {
            return;
        }
        if (!marketGateOpen()) {
            return;
        }
        scanCandidates(openSlots);
    }

    private void processHolding(AutoTradePosition p) {
        if (!MarketHours.isOpen(p.getMarket(), LocalDateTime.now())) {
            return; // 그 시장이 닫혀있으면 가격이 안 움직이므로 스킵(불필요한 API 호출 방지)
        }
        BigDecimal current = currentPrice(p.getSymbol());
        if (current == null) {
            return; // 콜드 캐시 — 다음 틱 재시도
        }
        BigDecimal peak = p.getPeakPrice().max(current);
        if (peak.compareTo(p.getPeakPrice()) > 0) {
            positionMapper.updatePeak(p.getId(), peak);
        }

        ExitReason exit = TrailingStopCalculator.decide(current, peak, p.getEntryPrice(),
                props.hardStopPct(), props.trailStopPct());
        if (exit == ExitReason.NONE && newsFadeDetector.hasNewsFaded(p.getSymbol())) {
            exit = ExitReason.NEWS_FADED;
        }
        if (exit != ExitReason.NONE) {
            // 결정근거 스냅샷(#832) — 하드/트레일 손절선은 판정에 쓴 바로 그 공식으로 재계산(부수효과 없음).
            String rationale = SellRationale.describe(exit, p.getEntryPrice(), peak, current,
                    TrailingStopCalculator.hardFloor(p.getEntryPrice(), props.hardStopPct()),
                    TrailingStopCalculator.trailFloor(peak, props.trailStopPct()));
            orderExecutor.sell(p, exit, current, rationale);
        }
    }

    /** 전체 평가손익(원금 대비) 계산 후 임계치 도달 시 트립 처리. */
    private boolean checkCircuitBreaker(AutoTradeState state) {
        if (state.isCircuitBreakerTripped()) {
            return true;
        }
        BigDecimal equity = state.getTotalBudget();
        for (AutoTradePosition p : positionMapper.findAll()) {
            BigDecimal qty = p.getEntryQty();
            if ("EXITED".equals(p.getStatus())) {
                equity = equity.add(p.getExitPrice().subtract(p.getEntryPrice()).multiply(qty));
            } else {
                BigDecimal current = currentPrice(p.getSymbol());
                if (current != null) {
                    equity = equity.add(current.subtract(p.getEntryPrice()).multiply(qty));
                }
            }
        }
        boolean trip = CircuitBreaker.check(equity, state.getTotalBudget(), props.circuitBreakerPct());
        if (trip) {
            stateMapper.tripCircuitBreaker(LocalDateTime.now());
            notify("🚨 서킷브레이커 발동 — 전체 평가손익이 -" + props.circuitBreakerPct()
                    + "% 도달, 신규 매수를 중단합니다. 재개는 수동 설정 변경 필요.");
        }
        return trip;
    }

    private boolean marketGateOpen() {
        Map<String, Object> breadth = dailyMapper.breadth(100);
        long up = numberOf(breadth == null ? null : breadth.get("UP"));
        long total = numberOf(breadth == null ? null : breadth.get("TOTAL"));
        int breadthPct = total > 0 ? (int) Math.round(up * 100.0 / total) : 50; // 데이터 없으면 중립값

        return MarketRegimeGate.evaluate(breadthPct, props.gate());
    }

    private void scanCandidates(int openSlots) {
        List<AutoTradeCandidate> candidates = candidateMapper.findActive();
        List<AutoTradePosition> holding = positionMapper.findHolding();
        int filled = 0;
        for (AutoTradeCandidate c : candidates) {
            if (filled >= openSlots) {
                break;
            }
            if (holding.stream().anyMatch(h -> h.getSymbol().equals(c.getSymbol()))) {
                continue; // 이미 보유 중
            }
            if (!MarketHours.isOpen(c.getMarket(), LocalDateTime.now())) {
                continue; // 그 시장이 지금 닫혀있음(KST 기준, 한국/미국 각각 판단)
            }
            if (newsFadeDetector.hasNewsFaded(c.getSymbol()) && !candidateDiscovery.retainDespiteNewsFade(c)) {
                continue; // 호재(S4↑) 없고, 저평가+상대강세 예외(#828)도 아님
            }
            List<DailyOhlcv> recent = dailyMapper.recentForSymbols(List.of(c.getSymbol()),
                    LocalDate.now().minusDays(props.volumeSpikeWindowDays() + 10));
            if (!PopularityChecker.isPopular(recent, props.volumeSpikeWindowDays(), props.volumeSpikeMultiplier(), props.priceMovePct())) {
                continue; // 인기 없음(거래량 스파이크도, 당일 가격 반응도 없음)
            }
            Valuation valuation = valuationClient.getValuation(c.getSymbol(), c.getMarket());
            boolean cheap = ValuationChecker.isUndervalued(valuation, props.maxPer(), props.maxPbr());
            boolean rerateCatalyst = capitalReturnCatalystDetector.hasRecentCatalyst(c.getSymbol());
            if (!cheap && !rerateCatalyst) {
                continue; // 이미 싼 것도 아니고, 자본배분(재평가) 촉매도 없음 — 원칙 §2/§3-7 둘 다 미달
            }
            FundamentalScore score = fundamentalScore(c, recent);
            if (!score.passes(props.minFundamentalPass())) {
                continue; // 원칙 §3 체크리스트(실적/재무/자본배분/시장성/상대강도) "대부분 YES" 미달
            }
            BigDecimal current = currentPrice(c.getSymbol());
            if (current == null) {
                continue;
            }
            String rationale = buyRationale(c, recent, valuation, cheap, rerateCatalyst, score);
            if (orderExecutor.buy(c.getSymbol(), c.getMarket(), props.perSymbolBudget(), current, rationale)) {
                filled++;
                notifyMacroContext(c); // 매수 게이트에는 안 넣음 — 참고용 거시 맥락만 별도 안내(2026-09-29)
            }
        }
    }

    /**
     * 매수 결정근거 스냅샷(#832) — 게이트 통과에 쓴 값들을 그대로 재사용해 포맷만 한다. 판정은 하지 않는다.
     * 인기 트리거 수치는 {@link PopularityChecker}의 동일 공식 추출값, 트리거 뉴스는 로컬 DB 1회 조회.
     */
    private String buyRationale(AutoTradeCandidate c, List<DailyOhlcv> recent, Valuation valuation,
                                 boolean cheap, boolean rerateCatalyst, FundamentalScore score) {
        String newsLabel = topActiveNewsLabel(c.getSymbol());
        // catalystHeadline 은 비워 둔다 — 촉매 뉴스도 결국 같은 활성 뉴스 집합에서 나오므로 "뉴스:" 절과
        // 중복된다. 촉매 경로 자체는 "(재평가촉매)" 태그로 드러난다.
        return BuyRationale.describe(valuation, cheap, rerateCatalyst, null, score,
                PopularityChecker.isVolumeSpike(recent, props.volumeSpikeWindowDays(), props.volumeSpikeMultiplier()),
                PopularityChecker.volumeRatio(recent, props.volumeSpikeWindowDays()),
                PopularityChecker.isPriceMoveSignificant(recent, props.priceMovePct()),
                PopularityChecker.priceMovePct(recent),
                newsLabel);
    }

    /**
     * 활성 뉴스 중 해당 종목 타겟 최고 레벨의 제목을 {@code S5 "제목"} 형태로. 없으면 null.
     * 로깅용 부가정보라 조회 실패가 매수를 막으면 안 됨 — 예외는 삼키고 null(= "뉴스:N/A").
     */
    private String topActiveNewsLabel(String symbol) {
        try {
            int bestLevel = 0;
            String bestTitle = null;
            for (StockNews n : newsMapper.active(symbol, 5)) {
                String level = NewsSignals.levelOf(n.getSentiment(), symbol);
                if (level == null) {
                    continue;
                }
                int lv = Integer.parseInt(level.substring(1));
                if (lv > bestLevel) {
                    bestLevel = lv;
                    bestTitle = n.getTitle();
                }
            }
            if (bestTitle == null) {
                return null;
            }
            String title = bestTitle.length() > MAX_NEWS_TITLE_LEN
                    ? bestTitle.substring(0, MAX_NEWS_TITLE_LEN) + "…" : bestTitle;
            return "S" + bestLevel + " \"" + title + "\"";
        } catch (RuntimeException e) {
            log.warn("결정근거용 뉴스 조회 실패(symbol={}) — 근거에 뉴스 생략: {}", symbol, e.toString());
            return null;
        }
    }

    /** 원칙 §3 체크리스트 중 자동화 가능한 5항목 채점. recentDays는 이미 조회된 인기(거래량) 판정용 목록 재사용. */
    private FundamentalScore fundamentalScore(AutoTradeCandidate c, List<DailyOhlcv> recentDays) {
        AnnualFinancials financials = valuationClient.getAnnualFinancials(c.getSymbol(), c.getMarket());
        boolean earnings = EarningsQualityChecker.hasThreeYearUptrend(financials);
        boolean balance = BalanceSheetChecker.isHealthy(financials, props.maxDebtRatio());
        boolean capitalReturn = CapitalReturnChecker.paysDividend(financials);
        BigDecimal liquidityThreshold = "US".equalsIgnoreCase(c.getMarket())
                ? props.minAvgTradingValueUsd() : props.minAvgTradingValue();
        boolean liquidity = LiquidityChecker.isLiquid(recentDays, liquidityThreshold);

        // 2026-10-05(#828 QA 발견): Toss 캔들 API가 US ETF(SPY)를 지원 안 해 US 상대강세가
        // 항상 데이터없음(false)으로 고정돼 있었음(실측: SPY/QQQ/VOO/IVV/DIA 전부 0건) — US만
        // 야후 차트 API(ValuationClient#getIndexReturnPct)로 대체, KR(069500)은 기존 경로 유지.
        List<DailyOhlcv> stockWindow = dailyMapper.recentForSymbols(List.of(c.getSymbol()),
                LocalDate.now().minusDays(props.relativeStrengthWindowDays() + 10));
        Double stockReturn = RelativeStrengthChecker.pctReturn(stockWindow);
        Double indexReturn = "US".equalsIgnoreCase(c.getMarket())
                ? valuationClient.getIndexReturnPct("SPY", props.relativeStrengthWindowDays())
                : RelativeStrengthChecker.pctReturn(dailyMapper.recentForSymbols(List.of("069500"),
                        LocalDate.now().minusDays(props.relativeStrengthWindowDays() + 10)));
        boolean relativeStrength = stockReturn != null && indexReturn != null
                && RelativeStrengthChecker.isRelativelyStrong(stockReturn, indexReturn);

        return new FundamentalScore(earnings, balance, capitalReturn, liquidity, relativeStrength);
    }

    /**
     * 거시 맥락 참고 정보(2026-09-29) — 매수 게이트가 아니라 사후 안내용. 섹터 최근 90일 호재
     * 누적건수(뉴스 attention 트렌드) + 분기(60일)·연(252일) 상대강도. KR만 지원(섹터/지수 데이터 한계).
     */
    private void notifyMacroContext(AutoTradeCandidate c) {
        if (!"KR".equalsIgnoreCase(c.getMarket())) {
            return;
        }
        String sector = universeMapper.findSectorBySymbol(c.getSymbol());
        if (sector == null) {
            return;
        }
        int hotCount90d = newsMapper.countSectorHotEvents(sector, LocalDateTime.now().minusDays(90));

        List<DailyOhlcv> stock60d = dailyMapper.recentForSymbols(List.of(c.getSymbol()), LocalDate.now().minusDays(70));
        List<DailyOhlcv> index60d = dailyMapper.recentForSymbols(List.of("069500"), LocalDate.now().minusDays(70));
        List<DailyOhlcv> stock252d = dailyMapper.recentForSymbols(List.of(c.getSymbol()), LocalDate.now().minusDays(262));
        List<DailyOhlcv> index252d = dailyMapper.recentForSymbols(List.of("069500"), LocalDate.now().minusDays(262));
        Double q = diffOrNull(RelativeStrengthChecker.pctReturn(stock60d), RelativeStrengthChecker.pctReturn(index60d));
        Double y = diffOrNull(RelativeStrengthChecker.pctReturn(stock252d), RelativeStrengthChecker.pctReturn(index252d));

        String msg = String.format(
                "📊 %s 거시 맥락(참고용, 매수판단엔 미반영) — 섹터(%s) 최근90일 호재 %d건, "
                        + "분기 상대강도 %s, 연간 상대강도 %s",
                c.getSymbol(), sector, hotCount90d,
                q == null ? "데이터부족" : String.format("%+.1f%%p", q),
                y == null ? "데이터부족" : String.format("%+.1f%%p", y));
        notify(msg);
    }

    private static Double diffOrNull(Double stockReturn, Double indexReturn) {
        return (stockReturn == null || indexReturn == null) ? null : stockReturn - indexReturn;
    }

    private BigDecimal currentPrice(String symbol) {
        List<TossPrice> prices = priceCache.get(List.of(symbol));
        if (prices.isEmpty() || prices.get(0).lastPrice() == null) {
            return null;
        }
        return new BigDecimal(prices.get(0).lastPrice());
    }

    private void notify(String content) {
        if (props.alertsEnabled()) {
            discord.send(props.webhookUrl(), content);
        }
    }

    private static long numberOf(Object o) {
        return o instanceof Number n ? n.longValue() : 0L;
    }
}
