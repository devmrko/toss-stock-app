package com.cloudhandson.tossstock.autotrade;

import com.cloudhandson.tossstock.briefing.DiscordClient;
import com.cloudhandson.tossstock.market.DailyOhlcv;
import com.cloudhandson.tossstock.market.DailyOhlcvMapper;
import com.cloudhandson.tossstock.market.UniverseMapper;
import com.cloudhandson.tossstock.news.StockNews;
import com.cloudhandson.tossstock.news.StockNewsMapper;
import com.cloudhandson.tossstock.toss.PriceCache;
import com.cloudhandson.tossstock.toss.dto.TossPrice;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * #832 — 스케줄러가 매수/매도 호출 시 결정근거 스냅샷을 조합해 넘기는지 검증(모킹).
 * 장 개장 여부는 실행 시각에 좌우되므로 {@link MarketHours}를 정적 모킹해 고정한다.
 * 설계: docs/design/832-decision-rationale-logging/README.md §10
 */
class AutoTradeSchedulerTest {

    private static final String SYMBOL = "005930";

    private AutoTradeStateMapper stateMapper;
    private AutoTradePositionMapper positionMapper;
    private AutoTradeCandidateMapper candidateMapper;
    private DailyOhlcvMapper dailyMapper;
    private StockNewsMapper newsMapper;
    private PriceCache priceCache;
    private NewsFadeDetector newsFadeDetector;
    private ValuationClient valuationClient;
    private CapitalReturnCatalystDetector catalystDetector;
    private UniverseMapper universeMapper;
    private CandidateDiscoveryService candidateDiscovery;
    private OrderExecutor orderExecutor;
    private AutoTradeScheduler scheduler;
    private MockedStatic<MarketHours> marketHours;

    /** 운영 yml 과 같은 형태. 펀더멘털 통과 기준만 1로 낮춰(재무데이터 없는 환경) 매수 경로를 끝까지 태운다. */
    private static AutoTradeProperties props() {
        return new AutoTradeProperties(true, BigDecimal.valueOf(5_000_000), 5,
                BigDecimal.valueOf(1_000_000), 15.0, 10.0, 10.0, "", "0 */5 9-15 * * MON-FRI",
                20, 1.5, 2.0, 20.0, 2.0, 200.0, BigDecimal.valueOf(500_000_000),
                BigDecimal.valueOf(350_000), 20, 1, 30, 60, 30, 8.0, new AutoTradeProperties.Gate(35));
    }

    @BeforeEach
    void setUp() {
        stateMapper = mock(AutoTradeStateMapper.class);
        positionMapper = mock(AutoTradePositionMapper.class);
        candidateMapper = mock(AutoTradeCandidateMapper.class);
        dailyMapper = mock(DailyOhlcvMapper.class);
        newsMapper = mock(StockNewsMapper.class);
        priceCache = mock(PriceCache.class);
        newsFadeDetector = mock(NewsFadeDetector.class);
        valuationClient = mock(ValuationClient.class);
        catalystDetector = mock(CapitalReturnCatalystDetector.class);
        universeMapper = mock(UniverseMapper.class);
        candidateDiscovery = mock(CandidateDiscoveryService.class);
        orderExecutor = mock(OrderExecutor.class);
        scheduler = new AutoTradeScheduler(props(), stateMapper, positionMapper, candidateMapper, dailyMapper,
                newsMapper, priceCache, newsFadeDetector, valuationClient, catalystDetector, universeMapper,
                candidateDiscovery, orderExecutor, mock(DiscordClient.class));
        marketHours = mockStatic(MarketHours.class);
        marketHours.when(() -> MarketHours.isOpen(anyString(), any(LocalDateTime.class))).thenReturn(true);
    }

    @AfterEach
    void tearDown() {
        marketHours.close();
    }

    private AutoTradeState state() {
        AutoTradeState s = new AutoTradeState();
        s.setDryRun(true);
        s.setTotalBudget(BigDecimal.valueOf(5_000_000));
        s.setPerSymbolBudget(BigDecimal.valueOf(1_000_000));
        return s;
    }

    @Test
    void sell_rationale_carries_binding_stop_peak_and_pnl() {
        AutoTradePosition p = new AutoTradePosition();
        p.setId(1L);
        p.setSymbol(SYMBOL);
        p.setMarket("KR");
        p.setEntryPrice(BigDecimal.valueOf(200_000));
        p.setEntryQty(BigDecimal.valueOf(5));
        p.setPeakPrice(BigDecimal.valueOf(235_000));
        p.setDryRun(true);
        when(stateMapper.find()).thenReturn(state());
        when(positionMapper.findHolding()).thenReturn(List.of(p));
        when(positionMapper.countHolding()).thenReturn(1);
        // 피크 235,000 대비 -10.43% → 추적손절선(211,500) 이탈
        when(priceCache.get(List.of(SYMBOL)))
                .thenReturn(List.of(new TossPrice(SYMBOL, "210500", "KRW", null)));

        scheduler.tick();

        ArgumentCaptor<String> rationale = ArgumentCaptor.forClass(String.class);
        verify(orderExecutor).sell(any(), eq(ExitReason.TRAIL_STOP), any(), rationale.capture());
        assertThat(rationale.getValue()).isEqualTo("트레일스탑(피크235000→현재210500,-10.43%) 진입200000 "
                + "수익+5.25% 스탑211500(하드180000/트레일211500)");
    }

    @Test
    void buy_rationale_carries_valuation_checklist_popularity_and_trigger_news() {
        AutoTradeCandidate c = new AutoTradeCandidate();
        c.setSymbol(SYMBOL);
        c.setMarket("KR");
        when(stateMapper.find()).thenReturn(state());
        when(positionMapper.findHolding()).thenReturn(List.of());
        when(positionMapper.countHolding()).thenReturn(0);
        when(positionMapper.realizedPnlTotal()).thenReturn(BigDecimal.ZERO); // #845
        when(dailyMapper.breadth(anyInt())).thenReturn(Map.of("UP", 60L, "TOTAL", 100L)); // 시장 게이트 통과
        when(candidateMapper.findActive()).thenReturn(List.of(c));
        when(newsFadeDetector.hasNewsFaded(SYMBOL)).thenReturn(false);
        when(dailyMapper.recentForSymbols(any(), any(LocalDate.class))).thenReturn(priceMoveBars());
        when(valuationClient.getValuation(SYMBOL, "KR"))
                .thenReturn(new Valuation(BigDecimal.valueOf(11.6), BigDecimal.valueOf(0.47)));
        when(catalystDetector.hasRecentCatalyst(SYMBOL)).thenReturn(false);
        when(priceCache.get(List.of(SYMBOL)))
                .thenReturn(List.of(new TossPrice(SYMBOL, "102000", "KRW", null)));
        when(newsMapper.active(SYMBOL, 5)).thenReturn(List.of(news("S4 재료", "005930:S4"),
                news("안랩, 보안주 급등", "005930:S5")));

        scheduler.tick();

        ArgumentCaptor<String> rationale = ArgumentCaptor.forClass(String.class);
        verify(orderExecutor).buy(eq(SYMBOL), eq("KR"), any(), any(), rationale.capture());
        assertThat(rationale.getValue())
                .startsWith("PER11.6/PBR0.47(저평가)")
                .contains("인기:가격+2%")                       // 거래량 데이터는 부족 → 가격 트리거만
                .contains("뉴스:S5 \"안랩, 보안주 급등\"")        // 최고 레벨 활성 뉴스 제목
                .containsPattern("펀더\\d/5\\(실적.재무.배당.유동.강도.\\)");
    }

    @Test
    void buy_rationale_falls_back_to_na_when_news_lookup_fails() {
        AutoTradeCandidate c = new AutoTradeCandidate();
        c.setSymbol(SYMBOL);
        c.setMarket("KR");
        when(stateMapper.find()).thenReturn(state());
        when(positionMapper.findHolding()).thenReturn(List.of());
        when(positionMapper.countHolding()).thenReturn(0);
        when(dailyMapper.breadth(anyInt())).thenReturn(Map.of("UP", 60L, "TOTAL", 100L));
        when(candidateMapper.findActive()).thenReturn(List.of(c));
        when(dailyMapper.recentForSymbols(any(), any(LocalDate.class))).thenReturn(priceMoveBars());
        when(valuationClient.getValuation(SYMBOL, "KR"))
                .thenReturn(new Valuation(BigDecimal.valueOf(11.6), BigDecimal.valueOf(0.47)));
        when(priceCache.get(List.of(SYMBOL)))
                .thenReturn(List.of(new TossPrice(SYMBOL, "102000", "KRW", null)));
        when(newsMapper.active(SYMBOL, 5)).thenThrow(new RuntimeException("ORA-00942: table not found"));

        scheduler.tick();

        ArgumentCaptor<String> rationale = ArgumentCaptor.forClass(String.class);
        verify(orderExecutor).buy(eq(SYMBOL), eq("KR"), any(), any(), rationale.capture());
        // 로깅용 조회 실패가 매수를 막지 않는다 — 뉴스만 N/A.
        assertThat(rationale.getValue()).endsWith("뉴스:N/A");
    }

    @Test
    void extreme_move_without_fundamental_catalyst_is_held_back() {
        // #838(2026-10-07, 안랩 실사례): 당일 +10%(임계값 8% 이상)인데 실적직결 촉매가 없으면
        // 저평가/펀더멘털을 다 통과해도 그 틱엔 매수하지 않는다.
        AutoTradeCandidate c = new AutoTradeCandidate();
        c.setSymbol(SYMBOL);
        c.setMarket("KR");
        when(stateMapper.find()).thenReturn(state());
        when(positionMapper.findHolding()).thenReturn(List.of());
        when(positionMapper.countHolding()).thenReturn(0);
        when(dailyMapper.breadth(anyInt())).thenReturn(Map.of("UP", 60L, "TOTAL", 100L));
        when(candidateMapper.findActive()).thenReturn(List.of(c));
        when(newsFadeDetector.hasNewsFaded(SYMBOL)).thenReturn(false);
        when(dailyMapper.recentForSymbols(any(), any(LocalDate.class))).thenReturn(extremeMoveBars());
        when(valuationClient.getValuation(SYMBOL, "KR"))
                .thenReturn(new Valuation(BigDecimal.valueOf(11.6), BigDecimal.valueOf(0.47)));
        when(catalystDetector.hasRecentCatalyst(SYMBOL)).thenReturn(false);
        when(priceCache.get(List.of(SYMBOL)))
                .thenReturn(List.of(new TossPrice(SYMBOL, "110000", "KRW", null)));

        scheduler.tick();

        verify(orderExecutor, org.mockito.Mockito.never()).buy(any(), any(), any(), any(), any());
    }

    @Test
    void extreme_move_with_fundamental_catalyst_still_buys() {
        // 같은 당일 +10%라도 실적직결 촉매(수주/계약 등)가 있으면 보류하지 않는다(066570류 사례).
        AutoTradeCandidate c = new AutoTradeCandidate();
        c.setSymbol(SYMBOL);
        c.setMarket("KR");
        when(stateMapper.find()).thenReturn(state());
        when(positionMapper.findHolding()).thenReturn(List.of());
        when(positionMapper.countHolding()).thenReturn(0);
        when(positionMapper.realizedPnlTotal()).thenReturn(BigDecimal.ZERO); // #845
        when(dailyMapper.breadth(anyInt())).thenReturn(Map.of("UP", 60L, "TOTAL", 100L));
        when(candidateMapper.findActive()).thenReturn(List.of(c));
        when(newsFadeDetector.hasNewsFaded(SYMBOL)).thenReturn(false);
        when(dailyMapper.recentForSymbols(any(), any(LocalDate.class))).thenReturn(extremeMoveBars());
        when(valuationClient.getValuation(SYMBOL, "KR"))
                .thenReturn(new Valuation(BigDecimal.valueOf(11.6), BigDecimal.valueOf(0.47)));
        when(catalystDetector.hasRecentCatalyst(SYMBOL)).thenReturn(true);
        when(priceCache.get(List.of(SYMBOL)))
                .thenReturn(List.of(new TossPrice(SYMBOL, "110000", "KRW", null)));

        scheduler.tick();

        verify(orderExecutor).buy(eq(SYMBOL), eq("KR"), any(), any(), any());
    }

    @Test
    void buy_is_blocked_shortly_after_a_stop_loss_exit_even_with_fresh_catalyst() {
        // #839 실사고 재발 방지(066570, 2026-10-07): 트레일스탑 매도 7초 뒤 더 비싼 가격으로
        // 재매수해 확정손실이 났음 — 완전히 새로운 신선한 호재(hasNewsFaded=false)여도 손절
        // 쿨다운(30분) 중이면 매수하지 않는다.
        AutoTradeCandidate c = new AutoTradeCandidate();
        c.setSymbol(SYMBOL);
        c.setMarket("KR");
        when(stateMapper.find()).thenReturn(state());
        when(positionMapper.findHolding()).thenReturn(List.of());
        when(positionMapper.countHolding()).thenReturn(0);
        when(dailyMapper.breadth(anyInt())).thenReturn(Map.of("UP", 60L, "TOTAL", 100L));
        when(candidateMapper.findActive()).thenReturn(List.of(c));
        when(positionMapper.lastStopExitAt(SYMBOL)).thenReturn(LocalDateTime.now().minusSeconds(7));

        scheduler.tick();

        verify(orderExecutor, org.mockito.Mockito.never()).buy(any(), any(), any(), any(), any());
        // 손절 쿨다운 자체로 막히므로, 그 뒤 단계(뉴스판정 등)는 아예 평가되지 않아야 한다.
        verify(newsFadeDetector, org.mockito.Mockito.never()).hasNewsFaded(any());
    }

    @Test
    void buy_proceeds_once_stop_loss_cooldown_elapsed() {
        AutoTradeCandidate c = new AutoTradeCandidate();
        c.setSymbol(SYMBOL);
        c.setMarket("KR");
        when(stateMapper.find()).thenReturn(state());
        when(positionMapper.findHolding()).thenReturn(List.of());
        when(positionMapper.countHolding()).thenReturn(0);
        when(positionMapper.realizedPnlTotal()).thenReturn(BigDecimal.ZERO); // #845
        when(dailyMapper.breadth(anyInt())).thenReturn(Map.of("UP", 60L, "TOTAL", 100L));
        when(candidateMapper.findActive()).thenReturn(List.of(c));
        when(positionMapper.lastStopExitAt(SYMBOL)).thenReturn(LocalDateTime.now().minusMinutes(31));
        when(newsFadeDetector.hasNewsFaded(SYMBOL)).thenReturn(false);
        when(dailyMapper.recentForSymbols(any(), any(LocalDate.class))).thenReturn(priceMoveBars());
        when(valuationClient.getValuation(SYMBOL, "KR"))
                .thenReturn(new Valuation(BigDecimal.valueOf(11.6), BigDecimal.valueOf(0.47)));
        when(catalystDetector.hasRecentCatalyst(SYMBOL)).thenReturn(false);
        when(priceCache.get(List.of(SYMBOL)))
                .thenReturn(List.of(new TossPrice(SYMBOL, "102000", "KRW", null)));

        scheduler.tick();

        verify(orderExecutor).buy(eq(SYMBOL), eq("KR"), any(), any(), any());
    }

    @Test
    void retention_path_buy_is_blocked_during_news_faded_cooldown() {
        // #835 QA(2026-10-07) 재발 방지: 뉴스 식었지만(hasNewsFaded) 저평가+상대강세라
        // retainDespiteNewsFade는 true인데도, 바로 직전(쿨다운 60분 이내)에 같은 종목이
        // NEWS_FADED로 청산된 적 있으면 재매수하지 않는다.
        AutoTradeCandidate c = new AutoTradeCandidate();
        c.setSymbol(SYMBOL);
        c.setMarket("KR");
        when(stateMapper.find()).thenReturn(state());
        when(positionMapper.findHolding()).thenReturn(List.of());
        when(positionMapper.countHolding()).thenReturn(0);
        when(dailyMapper.breadth(anyInt())).thenReturn(Map.of("UP", 60L, "TOTAL", 100L));
        when(candidateMapper.findActive()).thenReturn(List.of(c));
        when(newsFadeDetector.hasNewsFaded(SYMBOL)).thenReturn(true);
        when(candidateDiscovery.retainDespiteNewsFade(c)).thenReturn(true);
        when(positionMapper.lastNewsFadedExitAt(SYMBOL)).thenReturn(LocalDateTime.now().minusMinutes(5));

        scheduler.tick();

        verify(orderExecutor, org.mockito.Mockito.never()).buy(any(), any(), any(), any(), any());
    }

    @Test
    void retention_path_buy_proceeds_once_cooldown_elapsed() {
        AutoTradeCandidate c = new AutoTradeCandidate();
        c.setSymbol(SYMBOL);
        c.setMarket("KR");
        when(stateMapper.find()).thenReturn(state());
        when(positionMapper.findHolding()).thenReturn(List.of());
        when(positionMapper.countHolding()).thenReturn(0);
        when(positionMapper.realizedPnlTotal()).thenReturn(BigDecimal.ZERO); // #845
        when(dailyMapper.breadth(anyInt())).thenReturn(Map.of("UP", 60L, "TOTAL", 100L));
        when(candidateMapper.findActive()).thenReturn(List.of(c));
        when(newsFadeDetector.hasNewsFaded(SYMBOL)).thenReturn(true);
        when(candidateDiscovery.retainDespiteNewsFade(c)).thenReturn(true);
        // 쿨다운(60분)보다 오래 전에 청산됨 → 재매수 허용
        when(positionMapper.lastNewsFadedExitAt(SYMBOL)).thenReturn(LocalDateTime.now().minusHours(2));
        when(dailyMapper.recentForSymbols(any(), any(LocalDate.class))).thenReturn(priceMoveBars());
        when(valuationClient.getValuation(SYMBOL, "KR"))
                .thenReturn(new Valuation(BigDecimal.valueOf(11.6), BigDecimal.valueOf(0.47)));
        when(catalystDetector.hasRecentCatalyst(SYMBOL)).thenReturn(false);
        when(priceCache.get(List.of(SYMBOL)))
                .thenReturn(List.of(new TossPrice(SYMBOL, "102000", "KRW", null)));

        scheduler.tick();

        verify(orderExecutor).buy(eq(SYMBOL), eq("KR"), any(), any(), any());
    }

    /** 전일 100,000 → 당일 102,000(+2.0%, 가격 트리거). 거래량 윈도우(20일)는 일부러 부족하게 둔다. */
    private static List<DailyOhlcv> priceMoveBars() {
        LocalDate d0 = LocalDate.now().minusDays(1);
        return List.of(
                new DailyOhlcv(SYMBOL, d0, BigDecimal.valueOf(100_000), BigDecimal.valueOf(100_000),
                        BigDecimal.valueOf(100_000), BigDecimal.valueOf(100_000), 1_000_000L),
                new DailyOhlcv(SYMBOL, d0.plusDays(1), BigDecimal.valueOf(102_000), BigDecimal.valueOf(102_000),
                        BigDecimal.valueOf(102_000), BigDecimal.valueOf(102_000), 1_100_000L));
    }

    /** 전일 100,000 → 당일 110,000(+10.0%, #838 임계값 8% 초과하는 극단적 급등). */
    private static List<DailyOhlcv> extremeMoveBars() {
        LocalDate d0 = LocalDate.now().minusDays(1);
        return List.of(
                new DailyOhlcv(SYMBOL, d0, BigDecimal.valueOf(100_000), BigDecimal.valueOf(100_000),
                        BigDecimal.valueOf(100_000), BigDecimal.valueOf(100_000), 1_000_000L),
                new DailyOhlcv(SYMBOL, d0.plusDays(1), BigDecimal.valueOf(110_000), BigDecimal.valueOf(110_000),
                        BigDecimal.valueOf(110_000), BigDecimal.valueOf(110_000), 1_100_000L));
    }

    private static StockNews news(String title, String sentiment) {
        StockNews n = new StockNews();
        n.setTitle(title);
        n.setSentiment(sentiment);
        n.setKind("EVENT");
        return n;
    }
}
