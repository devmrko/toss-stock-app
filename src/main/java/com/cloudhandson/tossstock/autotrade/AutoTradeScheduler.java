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
        // 2026-10-07 실사고 수정: #828로 "뉴스 식었지만 저평가+상대강세"라서 산 포지션을 바로 다음
        // 틱에 "뉴스 식음"으로 또 팔아버리면, 판 직후 같은 조건으로 재매수돼 무한 매수-매도 반복으로
        // 이어짐(259630 실사례, 44회 왕복/실현손실 24,950원) — 매수 게이트(#828)에만 넣은 예외를
        // 매도 게이트에도 대칭으로 반영. 지금도 저평가+상대강세면 뉴스 소멸을 매도 이유로 안 본다.
        if (exit == ExitReason.NONE && newsFadeDetector.hasNewsFaded(p.getSymbol())
                && !candidateDiscovery.isCheapAndRelativelyStrong(p.getSymbol(), p.getMarket())) {
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
        // #845(2026-10-07): findAll()로 전체 이력(청산 포함)을 매 틱 Java로 재합산하던 것을
        // realized는 DB SUM 1건(행 수 늘어나도 빠름)으로, unrealized는 보유 중인 적은 수만 조회.
        BigDecimal realized = positionMapper.realizedPnlTotal();
        BigDecimal equity = state.getTotalBudget().add(realized == null ? BigDecimal.ZERO : realized);
        for (AutoTradePosition p : positionMapper.findHolding()) {
            try {
                BigDecimal current = currentPrice(p.getSymbol());
                if (current != null) {
                    equity = equity.add(current.subtract(p.getEntryPrice()).multiply(p.getEntryQty()));
                }
            } catch (RuntimeException e) {
                // 원래 findAll() 시절부터 있던 잠재적 결함(개별 종목 시세조회 실패가 전체 틱을
                // 죽임) — #845 리팩터링 중 발견해 같이 고침. 한 종목 실패가 서킷브레이커 판정
                // 전체를 막으면 안 됨(§기존 tick()의 processHolding 격리 원칙과 동일).
                log.warn("서킷브레이커 평가손익 계산 중 시세조회 실패(symbol={}): {}", p.getSymbol(), e.toString());
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
            if (recentlyExitedViaStop(c.getSymbol())) {
                continue; // #839 실사고 수정 — 손절 직후 즉시 재진입하면 더 비싼 가격에 되사는
                          // 확정손실 왕복이 날 수 있음(066570 실사례, 7초 후 212,000→212,500
                          // 재매수). 뉴스/촉매 신선도와 무관하게 전체 매수 경로에 적용.
            }
            if (candidateDiscovery.isSameThemeAsRecentStopExit(c.getSymbol(), c.getMarket())) {
                continue; // #840 — 오늘 손절된 포지션과 같은 테마(같은 스토리의 다음 기사)로
                          // 재진입하려는 경우, #839 쿨다운이 끝났어도 당일은 계속 차단.
            }
            if (newsFadeDetector.hasNewsFaded(c.getSymbol())) {
                if (!candidateDiscovery.retainDespiteNewsFade(c)) {
                    continue; // 호재(S4↑) 없고, 저평가+상대강세 예외(#828)도 아님
                }
                if (recentlyExitedViaNewsFade(c.getSymbol())) {
                    continue; // #828 유지경로 쿨다운(#835 QA) — 뉴스소멸 매도 직후 같은 경로로 바로 재매수 금지
                }
            }
            List<DailyOhlcv> recent = dailyMapper.recentForSymbols(List.of(c.getSymbol()),
                    LocalDate.now().minusDays(props.volumeSpikeWindowDays() + 10));
            if (!PopularityChecker.isPopular(recent, props.volumeSpikeWindowDays(), props.volumeSpikeMultiplier(), props.priceMovePct())) {
                continue; // 인기 없음(거래량 스파이크도, 당일 가격 반응도 없음)
            }
            Valuation valuation = valuationClient.getValuation(c.getSymbol(), c.getMarket());
            boolean cheap = ValuationChecker.isUndervalued(valuation, props.maxPer(), props.maxPbr());
            boolean rerateCatalyst = capitalReturnCatalystDetector.hasRecentCatalyst(c.getSymbol());
            // #855(2026-10-08 실측): 촉매 키워드만으로 저평가·급등 필터를 통째로 면제하던 구멍 차단.
            // 포스코퓨처엠(PER 392.49)·삼성SDI(PER 데이터없음)가 "6조 수주" 키워드 하나로 전 필터를
            // 면제받고 매수돼 손실. 이미 기대가 극단적으로 반영된 가격엔 어떤 촉매도 추가 상승을
            // 정당화하지 못한다고 보고, 면제에 밸류에이션 상한(저평가 기준의 N배)을 건다.
            boolean catalystAllowed = rerateCatalyst && ValuationChecker.withinCatalystBound(
                    valuation, props.maxPer() * props.catalystValuationMultiple(),
                    props.maxPbr() * props.catalystValuationMultiple());
            // #838(2026-10-07, 안랩/053800 실사례): 당일 급등이 실적직결 촉매 없는 순수 테마성
            // 반응이면 단기 트레이더 쏠림→되돌림 위험이 커서 당일 매수는 보류한다(날짜 추적 없이
            // "당일 변동"만 봄 — 다음 거래일엔 자연히 재평가됨). 신규 I/O 없이 이미 계산된
            // recent/rerateCatalyst만 재사용.
            if (PopularityChecker.priceMovePct(recent) >= props.extremeMovePct() && !catalystAllowed) {
                continue; // 테마성 과열(실적직결 촉매 없는 당일 급등) — 오늘은 매수 보류
            }
            if (!cheap && !catalystAllowed) {
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
    /**
     * NEWS_FADED로 청산된 지 news-faded-cooldown-minutes 이내인지 — #828 유지경로(저평가+상대강세)
     * 재매수 쿨다운(#835 QA, 2026-10-07). 판정이 임계값 근처에서 흔들려 매도 직후 바로 같은 경로로
     * 재매수되는 걸 막는다(신규 호재는 이 쿨다운과 무관하게 정상 매수 가능 — hasNewsFaded가 false면
     * 이 메서드 자체가 호출되지 않음).
     */
    private boolean recentlyExitedViaNewsFade(String symbol) {
        LocalDateTime lastExit = positionMapper.lastNewsFadedExitAt(symbol);
        return lastExit != null
                && lastExit.isAfter(LocalDateTime.now().minusMinutes(props.newsFadedCooldownMinutes()));
    }

    /**
     * 하드/트레일스탑으로 손절된 지 stop-exit-cooldown-minutes 이내인지(#839 실사고, 2026-10-07).
     * 066570 실사례: 트레일스탑 매도(212,000) 7초 뒤 더 비싼 가격(212,500)으로 재매수해 가격차만으로
     * 확정손실. 새 촉매가 진짜로 신선해도 손절 직후 즉시 재진입은 수수료·세금(별도 QA 발견, #839)까지
     * 감안하면 더 불리하므로, 뉴스/촉매 신선도와 무관하게 전체 매수 경로 앞단에서 차단한다.
     */
    private boolean recentlyExitedViaStop(String symbol) {
        LocalDateTime lastExit = positionMapper.lastStopExitAt(symbol);
        return lastExit != null
                && lastExit.isAfter(LocalDateTime.now().minusMinutes(props.stopExitCooldownMinutes()));
    }

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
