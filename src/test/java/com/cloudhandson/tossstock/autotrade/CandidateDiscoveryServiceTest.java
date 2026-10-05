package com.cloudhandson.tossstock.autotrade;

import com.cloudhandson.tossstock.market.DailyCollector;
import com.cloudhandson.tossstock.market.DailyOhlcv;
import com.cloudhandson.tossstock.market.DailyOhlcvMapper;
import com.cloudhandson.tossstock.market.UniverseMapper;
import com.cloudhandson.tossstock.news.StockNews;
import com.cloudhandson.tossstock.news.StockNewsMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * CandidateDiscoveryService 검증 — 후보 자동등록/자동해제(#808 2026-09-29) +
 * 뉴스-독립 후보 유지(#828 2026-10-05, 설계 docs/design/828-news-independent-retention).
 */
class CandidateDiscoveryServiceTest {

    private StockNewsMapper newsMapper;
    private AutoTradeCandidateMapper candidateMapper;
    private UniverseMapper universeMapper;
    private NewsFadeDetector newsFadeDetector;
    private DailyCollector dailyCollector;
    private ValuationClient valuationClient;
    private DailyOhlcvMapper dailyMapper;
    private CandidateDiscoveryService service;

    @BeforeEach
    void setUp() {
        newsMapper = mock(StockNewsMapper.class);
        candidateMapper = mock(AutoTradeCandidateMapper.class);
        universeMapper = mock(UniverseMapper.class);
        newsFadeDetector = mock(NewsFadeDetector.class);
        dailyCollector = mock(DailyCollector.class);
        valuationClient = mock(ValuationClient.class);
        dailyMapper = mock(DailyOhlcvMapper.class);
        AutoTradeProperties props = new AutoTradeProperties(true, BigDecimal.valueOf(3_000_000), 5,
                BigDecimal.valueOf(600_000), 15.0, 10.0, 10.0, "", "0 * * * * *", 20, 1.0, 1.5, 30.0, 3.0, 200.0,
                BigDecimal.valueOf(300_000_000), BigDecimal.valueOf(200_000), 20, 2, 30,
                new AutoTradeProperties.Gate(20));
        service = new CandidateDiscoveryService(newsMapper, candidateMapper, universeMapper, newsFadeDetector,
                dailyCollector, props, valuationClient, dailyMapper);
        when(candidateMapper.findActive()).thenReturn(List.of());
    }

    private static StockNews news(String targets, String sentiment, String title) {
        StockNews n = new StockNews();
        n.setTargets(targets);
        n.setSentiment(sentiment);
        n.setTitle(title);
        return n;
    }

    @Test
    void kr_symbol_with_strong_sentiment_is_registered() {
        when(newsMapper.findRecentEvents(any())).thenReturn(List.of(
                news("009150,전자부품", "009150:S5,전자부품:S4", "삼성전기 기판 증설")));
        when(candidateMapper.existsActive("009150")).thenReturn(false);

        service.refresh();

        verify(candidateMapper).insert(argThat(c -> c.getSymbol().equals("009150") && c.getMarket().equals("KR")));
    }

    @Test
    void kr_symbol_registration_does_not_trigger_backfill() {
        // KR은 DailyCollector 정기 전종목 스캔이 이미 커버 — 후보 등록 때 중복 백필 불필요.
        when(newsMapper.findRecentEvents(any())).thenReturn(List.of(
                news("009150,전자부품", "009150:S5,전자부품:S4", "삼성전기 기판 증설")));
        when(candidateMapper.existsActive("009150")).thenReturn(false);

        service.refresh();

        verify(dailyCollector, never()).backfillSymbol(anyString(), any());
    }

    @Test
    void us_ticker_verified_against_universe_is_registered() {
        when(newsMapper.findRecentEvents(any())).thenReturn(List.of(
                news("NVDA,반도체", "NVDA:S5,반도체:S4", "엔비디아 자사주 매입")));
        when(candidateMapper.existsActive("NVDA")).thenReturn(false);
        when(universeMapper.existsUsSymbol("NVDA")).thenReturn(true);

        service.refresh();

        verify(candidateMapper).insert(argThat(c -> c.getSymbol().equals("NVDA") && c.getMarket().equals("US")));
    }

    @Test
    void us_ticker_registration_triggers_backfill() {
        // 2026-09-30 버그 수정: 신규 US 후보는 daily_ohlcv가 없으면 인기/상대강도 판정이
        // 영원히 false가 되던 문제 — 등록 시 백필을 트리거해야 함.
        when(newsMapper.findRecentEvents(any())).thenReturn(List.of(
                news("MSFT,IT", "MSFT:S5,IT:S4", "Microsoft 28년 만의 최대 분기 상승")));
        when(candidateMapper.existsActive("MSFT")).thenReturn(false);
        when(universeMapper.existsUsSymbol("MSFT")).thenReturn(true);

        service.refresh();

        verify(dailyCollector).backfillSymbol("MSFT", null);
    }

    @Test
    void sector_only_target_not_registered() {
        when(newsMapper.findRecentEvents(any())).thenReturn(List.of(
                news("반도체", "반도체:S4", "반도체 업황 개선")));

        service.refresh();

        verify(candidateMapper, never()).insert(any());
    }

    @Test
    void weak_sentiment_below_s4_not_registered() {
        when(newsMapper.findRecentEvents(any())).thenReturn(List.of(
                news("009150", "009150:S3", "삼성전기 단순 공시")));

        service.refresh();

        verify(candidateMapper, never()).insert(any());
    }

    @Test
    void already_active_symbol_not_duplicated() {
        when(newsMapper.findRecentEvents(any())).thenReturn(List.of(
                news("009150", "009150:S5", "삼성전기 추가 뉴스")));
        when(candidateMapper.existsActive("009150")).thenReturn(true);

        service.refresh();

        verify(candidateMapper, never()).insert(any());
    }

    @Test
    void unverified_uppercase_token_not_registered() {
        when(newsMapper.findRecentEvents(any())).thenReturn(List.of(
                news("ABCDE", "ABCDE:S5", "알 수 없는 토큰")));
        when(universeMapper.existsUsSymbol("ABCDE")).thenReturn(false);

        service.refresh();

        verify(candidateMapper, never()).insert(any());
    }

    @Test
    void faded_candidate_is_deactivated() {
        when(newsMapper.findRecentEvents(any())).thenReturn(List.of());
        AutoTradeCandidate existing = new AutoTradeCandidate();
        existing.setSymbol("195870");
        when(candidateMapper.findActive()).thenReturn(List.of(existing));
        when(newsFadeDetector.hasNewsFaded("195870")).thenReturn(true);

        service.refresh();

        verify(candidateMapper).deactivate("195870");
    }

    // ── #828 뉴스-독립 후보 유지 ──────────────────────────────────────────────
    // 실사례(2026-10-05): SMCI — PER 13.40/PBR 2.80로 저평가, 9/14 종가 36.74 → 10/1 종가 41.15
    // (+12%)로 지수 대비 상대강세였는데 "Vera Rubin NVL72 출하" 뉴스 TTL이 끝나자 자동해제됨.
    private static final BigDecimal SMCI_PER = BigDecimal.valueOf(13.40);
    private static final BigDecimal SMCI_PBR = BigDecimal.valueOf(2.80);

    private AutoTradeCandidate fadedCandidate(String symbol, String market, LocalDateTime createdAt) {
        AutoTradeCandidate c = new AutoTradeCandidate();
        c.setSymbol(symbol);
        c.setMarket(market);
        c.setCreatedAt(createdAt);
        when(newsMapper.findRecentEvents(any())).thenReturn(List.of());
        when(candidateMapper.findActive()).thenReturn(List.of(c));
        when(newsFadeDetector.hasNewsFaded(symbol)).thenReturn(true);
        return c;
    }

    private static DailyOhlcv day(LocalDate date, double close) {
        DailyOhlcv o = new DailyOhlcv();
        o.setTradeDate(date);
        o.setCloseP(BigDecimal.valueOf(close));
        return o;
    }

    /** 9/14 → 10/1 종가 2건(상대강도 계산은 첫날/마지막날 종가만 사용). */
    private static List<DailyOhlcv> window(double firstClose, double lastClose) {
        return List.of(day(LocalDate.of(2026, 9, 14), firstClose), day(LocalDate.of(2026, 10, 1), lastClose));
    }

    private void givenPriceWindows(String symbol, List<DailyOhlcv> stock, List<DailyOhlcv> index) {
        when(dailyMapper.recentForSymbols(eq(List.of(symbol)), any())).thenReturn(stock);
        when(dailyMapper.recentForSymbols(eq(List.of("SPY")), any())).thenReturn(index);
    }

    @Test
    void faded_but_cheap_and_relatively_strong_candidate_is_retained() {
        fadedCandidate("SMCI", "US", LocalDateTime.now().minusDays(10));
        givenPriceWindows("SMCI", window(36.74, 41.15), window(600.00, 612.00)); // +12.0% vs +2.0%
        when(valuationClient.getValuation("SMCI", "US")).thenReturn(new Valuation(SMCI_PER, SMCI_PBR));

        service.refresh();

        verify(candidateMapper, never()).deactivate(anyString());
    }

    @Test
    void faded_and_cheap_but_not_relatively_strong_is_deactivated() {
        fadedCandidate("SMCI", "US", LocalDateTime.now().minusDays(10));
        givenPriceWindows("SMCI", window(41.15, 36.74), window(600.00, 612.00)); // -10.7% vs +2.0%
        when(valuationClient.getValuation("SMCI", "US")).thenReturn(new Valuation(SMCI_PER, SMCI_PBR));

        service.refresh();

        verify(candidateMapper).deactivate("SMCI");
    }

    @Test
    void faded_and_relatively_strong_but_expensive_is_deactivated() {
        fadedCandidate("SMCI", "US", LocalDateTime.now().minusDays(10));
        givenPriceWindows("SMCI", window(36.74, 41.15), window(600.00, 612.00));
        when(valuationClient.getValuation("SMCI", "US"))
                .thenReturn(new Valuation(BigDecimal.valueOf(45.0), BigDecimal.valueOf(8.0))); // max-per/pbr 초과

        service.refresh();

        verify(candidateMapper).deactivate("SMCI");
    }

    @Test
    void faded_candidate_beyond_max_retention_days_is_deactivated_even_if_cheap_and_strong() {
        fadedCandidate("SMCI", "US", LocalDateTime.now().minusDays(40)); // 상한 30일 초과
        givenPriceWindows("SMCI", window(36.74, 41.15), window(600.00, 612.00));
        when(valuationClient.getValuation("SMCI", "US")).thenReturn(new Valuation(SMCI_PER, SMCI_PBR));

        service.refresh();

        verify(candidateMapper).deactivate("SMCI");
    }

    @Test
    void valuation_lookup_failure_deactivates_faded_candidate() {
        // fail-closed(설계 §9) — 외부 API 실패로 판정 불가면 유지하지 않는다.
        fadedCandidate("SMCI", "US", LocalDateTime.now().minusDays(10));
        givenPriceWindows("SMCI", window(36.74, 41.15), window(600.00, 612.00));
        when(valuationClient.getValuation("SMCI", "US")).thenReturn(null);

        service.refresh();

        verify(candidateMapper).deactivate("SMCI");
    }

    @Test
    void missing_price_history_deactivates_faded_candidate() {
        // 일봉이 부족해 상대강도 판정 자체가 불가 → fail-closed(설계 §9).
        fadedCandidate("SMCI", "US", LocalDateTime.now().minusDays(10));
        givenPriceWindows("SMCI", List.of(), window(600.00, 612.00));
        when(valuationClient.getValuation("SMCI", "US")).thenReturn(new Valuation(SMCI_PER, SMCI_PBR));

        service.refresh();

        verify(candidateMapper).deactivate("SMCI");
    }

    @Test
    void still_hot_candidate_not_deactivated() {
        when(newsMapper.findRecentEvents(any())).thenReturn(List.of());
        AutoTradeCandidate existing = new AutoTradeCandidate();
        existing.setSymbol("009150");
        when(candidateMapper.findActive()).thenReturn(List.of(existing));
        when(newsFadeDetector.hasNewsFaded("009150")).thenReturn(false);

        service.refresh();

        verify(candidateMapper, never()).deactivate(anyString());
    }
}
