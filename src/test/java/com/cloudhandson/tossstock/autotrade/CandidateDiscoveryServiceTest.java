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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
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
    private AutoTradePositionMapper positionMapper;
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
        positionMapper = mock(AutoTradePositionMapper.class);
        AutoTradeProperties props = new AutoTradeProperties(true, BigDecimal.valueOf(3_000_000), 5,
                BigDecimal.valueOf(600_000), 15.0, 10.0, 10.0, "", "0 * * * * *", 20, 1.0, 1.5, 30.0, 3.0, 200.0,
                BigDecimal.valueOf(300_000_000), BigDecimal.valueOf(200_000), 20, 2, 30, 60, 30, 6.0, 5, 3.0,
                new AutoTradeProperties.Gate(20));
        service = new CandidateDiscoveryService(newsMapper, candidateMapper, universeMapper, newsFadeDetector,
                dailyCollector, props, valuationClient, dailyMapper, positionMapper);
        when(candidateMapper.findActive()).thenReturn(List.of());
    }

    /** #865 촉매 자격을 충족하는 facts — 등급 외 조건은 통과시키고 등급 로직만 보는 용도. */
    private static final String QUALIFIED_FACTS = """
            {"confirmed":true,"isTransaction":true,"materialAmount":true,
             "priceAlreadyMoved":false,"beneficiary":"SELLER","riskFlag":"NONE"}""";

    private static StockNews news(String targets, String sentiment, String title) {
        StockNews n = news(targets, sentiment, title, QUALIFIED_FACTS);
        return n;
    }

    private static StockNews news(String targets, String sentiment, String title, String facts) {
        StockNews n = new StockNews();
        n.setTargets(targets);
        n.setSentiment(sentiment);
        n.setTitle(title);
        n.setFacts(facts);
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

    // ---- #865 촉매 자격 게이트 ----

    @Test
    void facts_없는_기사는_s5라도_등록되지_않는다() {
        // fail-closed — LLM 추출 실패·구버전 행은 매수 근거가 없다(인수조건 5).
        when(newsMapper.findRecentEvents(any())).thenReturn(List.of(
                news("009150", "009150:S5", "삼성전기 뉴스", null)));
        when(candidateMapper.existsActive("009150")).thenReturn(false);

        service.refresh();

        verify(candidateMapper, never()).insert(any());
    }

    @Test
    void 규모_미제시_기사는_등록되지_않는다() {
        // "금액 미공개 MOU" 류 — 원칙 §3-1 '구체 촉매'가 아니다.
        when(newsMapper.findRecentEvents(any())).thenReturn(List.of(
                news("009150", "009150:S4", "삼성전기, 업무협약 체결",
                        """
                        {"confirmed":true,"isTransaction":true,"materialAmount":false,
                         "priceAlreadyMoved":false,"beneficiary":"SELLER","riskFlag":"NONE"}""")));
        when(candidateMapper.existsActive("009150")).thenReturn(false);

        service.refresh();

        verify(candidateMapper, never()).insert(any());
    }

    @Test
    void 리스크_이벤트는_호재와_함께여도_등록되지_않는다() {
        // 인수조건 2 — 유상증자가 섞이면 긍정 사실로 상쇄하지 않는다(원칙 §3-3).
        when(newsMapper.findRecentEvents(any())).thenReturn(List.of(
                news("009150", "009150:S5", "삼성전기, 대규모 수주와 함께 유상증자 결정",
                        """
                        {"confirmed":true,"isTransaction":true,"materialAmount":true,
                         "recurring":true,"secularDemand":true,"exportGlobal":true,
                         "priceAlreadyMoved":false,"beneficiary":"SELLER","riskFlag":"DILUTION"}""")));
        when(candidateMapper.existsActive("009150")).thenReturn(false);

        service.refresh();

        verify(candidateMapper, never()).insert(any());
    }

    @Test
    void 선반영_사후보도_기사는_등록되지_않는다() {
        when(newsMapper.findRecentEvents(any())).thenReturn(List.of(
                news("009150", "009150:S5", "[특징주] 삼성전기 급등",
                        """
                        {"confirmed":true,"isTransaction":false,"materialAmount":true,
                         "priceAlreadyMoved":true,"beneficiary":"NEITHER","riskFlag":"NONE"}""")));
        when(candidateMapper.existsActive("009150")).thenReturn(false);

        service.refresh();

        verify(candidateMapper, never()).insert(any());
    }

    // ---- #869 구 게이트 후보 재심사 ----

    private static AutoTradeCandidate candidate(String symbol, String note) {
        AutoTradeCandidate c = new AutoTradeCandidate();
        c.setSymbol(symbol);
        c.setMarket("KR");
        c.setValuationNote(note);
        return c;
    }

    @Test
    void 구게이트_등록_후보는_재심사로_해제된다() {
        // 통과율 37% 시절 들어온 후보 — 노트에 촉매점수가 없다.
        when(candidateMapper.findActive()).thenReturn(List.of(
                candidate("145170", "자동발견(2026-10-07T09:00:15.300134): 노브랜드 버거, 대학가 매장 확대")));
        when(newsMapper.findRecentEvents(any())).thenReturn(List.of());

        service.refresh();

        verify(candidateMapper).deactivate("145170");
    }

    @Test
    void 새_게이트로_등록된_정상_후보는_유지된다() {
        when(candidateMapper.findActive()).thenReturn(List.of(
                candidate("267260", "자동발견(2026-10-08T17:15:01, 촉매점수 3/4): "
                        + "HD건설기계, 美 데이터센터 발전엔진 '롱블록' 수주…3900억 규모")));
        when(newsMapper.findRecentEvents(any())).thenReturn(List.of());
        when(newsFadeDetector.hasNewsFaded("267260")).thenReturn(false);

        service.refresh();

        verify(candidateMapper, never()).deactivate("267260");
    }

    @Test
    void 새_게이트로_등록됐어도_제목이_가드에_걸리면_해제된다() {
        // 인수조건 8 — 1차 배포에서 443060 만 남은 누락. 노트에 촉매점수가 있어 '새 기준
        // 등록'으로 분류됐지만 그게 바로 가드가 잡으려던 인수 결함 그 자체였다.
        when(candidateMapper.findActive()).thenReturn(List.of(
                candidate("443060", "자동발견(2026-10-08T17:15:01.516288, 촉매점수 3/4): "
                        + "HD현대마린솔루션, 美 엔진 기업 '골텐스' 3315억원에 인수")));
        when(newsMapper.findRecentEvents(any())).thenReturn(List.of());

        service.refresh();

        verify(candidateMapper).deactivate("443060");
    }

    @Test
    void 해제사유_판정_순서() {
        assertThat(CandidateDiscoveryService.disqualifyReason("수동 등록: 장기 관찰")).isNull();
        assertThat(CandidateDiscoveryService.disqualifyReason("자동발견(t): 아무 제목"))
                .isEqualTo("구 게이트 등록분");
        assertThat(CandidateDiscoveryService.disqualifyReason(
                "자동발견(t, 촉매점수 2/4): A사, 2조 적자")).isEqualTo("제목판정 LOSS");
        assertThat(CandidateDiscoveryService.disqualifyReason(
                "자동발견(t, 촉매점수 2/4): A사, B사 3315억원에 인수")).isEqualTo("제목판정 BUYER");
        assertThat(CandidateDiscoveryService.disqualifyReason(
                "자동발견(t, 촉매점수 2/4): A사, 3900억 규모 수주")).isNull();
        assertThat(CandidateDiscoveryService.disqualifyReason(null)).isNull();
    }

    @Test
    void 노트에서_제목만_떼어낸다() {
        assertThat(CandidateDiscoveryService.titleOf("자동발견(t, 촉매점수 3/4): 제목 부분"))
                .isEqualTo("제목 부분");
        // 형식이 다르면 노트 전체를 제목으로 본다(가드가 과차단 쪽으로 기운다).
        assertThat(CandidateDiscoveryService.titleOf("형식이 다른 노트")).isEqualTo("형식이 다른 노트");
    }

    @Test
    void 수동_등록_후보는_재심사로_해제되지_않는다() {
        // 인수조건 7 — 사람이 넣은 것은 자동 판정으로 뺄 권한이 없다.
        when(candidateMapper.findActive()).thenReturn(List.of(
                candidate("005930", "수동 등록: 장기 관찰")));
        when(newsMapper.findRecentEvents(any())).thenReturn(List.of());
        when(newsFadeDetector.hasNewsFaded("005930")).thenReturn(false);

        service.refresh();

        verify(candidateMapper, never()).deactivate("005930");
    }

    @Test
    void 촉매점수가_노트에_기록된다() {
        // 인수조건 3 — 판정 근거의 설명 가능성.
        when(newsMapper.findRecentEvents(any())).thenReturn(List.of(
                news("009150", "009150:S5", "삼성전기, 3900억 장기 수출 공급계약",
                        """
                        {"confirmed":true,"isTransaction":true,"materialAmount":true,
                         "recurring":true,"secularDemand":true,"exportGlobal":true,
                         "shareholderReturn":true,
                         "priceAlreadyMoved":false,"beneficiary":"SELLER","riskFlag":"NONE"}""")));
        when(candidateMapper.existsActive("009150")).thenReturn(false);

        service.refresh();

        verify(candidateMapper).insert(argThat(c -> c.getValuationNote().contains("촉매점수 4/4")));
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

    /**
     * 2026-10-05 QA: Toss 캔들 API가 US ETF(SPY)를 지원 안 해(실측: SPY/QQQ/VOO/IVV/DIA 전부
     * 0건) 지수 수익률을 {@code ValuationClient#getIndexReturnPct}(야후 차트 API)로 대체—
     * US는 이걸로 스텁, KR(069500)은 여전히 {@code dailyMapper} 경로.
     */
    private void givenPriceWindows(String symbol, List<DailyOhlcv> stock, Double spyReturnPct) {
        when(dailyMapper.recentForSymbols(eq(List.of(symbol)), any())).thenReturn(stock);
        when(valuationClient.getIndexReturnPct(eq("SPY"), anyInt())).thenReturn(spyReturnPct);
    }

    @Test
    void faded_but_cheap_and_relatively_strong_candidate_is_retained() {
        fadedCandidate("SMCI", "US", LocalDateTime.now().minusDays(10));
        givenPriceWindows("SMCI", window(36.74, 41.15), 2.0); // +12.0% vs SPY +2.0%
        when(valuationClient.getValuation("SMCI", "US")).thenReturn(new Valuation(SMCI_PER, SMCI_PBR));

        service.refresh();

        verify(candidateMapper, never()).deactivate(anyString());
    }

    @Test
    void faded_and_cheap_but_not_relatively_strong_is_deactivated() {
        fadedCandidate("SMCI", "US", LocalDateTime.now().minusDays(10));
        givenPriceWindows("SMCI", window(41.15, 36.74), 2.0); // -10.7% vs SPY +2.0%
        when(valuationClient.getValuation("SMCI", "US")).thenReturn(new Valuation(SMCI_PER, SMCI_PBR));

        service.refresh();

        verify(candidateMapper).deactivate("SMCI");
    }

    @Test
    void faded_and_relatively_strong_but_expensive_is_deactivated() {
        fadedCandidate("SMCI", "US", LocalDateTime.now().minusDays(10));
        givenPriceWindows("SMCI", window(36.74, 41.15), 2.0);
        when(valuationClient.getValuation("SMCI", "US"))
                .thenReturn(new Valuation(BigDecimal.valueOf(45.0), BigDecimal.valueOf(8.0))); // max-per/pbr 초과

        service.refresh();

        verify(candidateMapper).deactivate("SMCI");
    }

    @Test
    void faded_candidate_beyond_max_retention_days_is_deactivated_even_if_cheap_and_strong() {
        fadedCandidate("SMCI", "US", LocalDateTime.now().minusDays(40)); // 상한 30일 초과
        givenPriceWindows("SMCI", window(36.74, 41.15), 2.0);
        when(valuationClient.getValuation("SMCI", "US")).thenReturn(new Valuation(SMCI_PER, SMCI_PBR));

        service.refresh();

        verify(candidateMapper).deactivate("SMCI");
    }

    @Test
    void valuation_lookup_failure_deactivates_faded_candidate() {
        // fail-closed(설계 §9) — 외부 API 실패로 판정 불가면 유지하지 않는다.
        fadedCandidate("SMCI", "US", LocalDateTime.now().minusDays(10));
        givenPriceWindows("SMCI", window(36.74, 41.15), 2.0);
        when(valuationClient.getValuation("SMCI", "US")).thenReturn(null);

        service.refresh();

        verify(candidateMapper).deactivate("SMCI");
    }

    @Test
    void missing_price_history_deactivates_faded_candidate() {
        // 일봉이 부족해 상대강도 판정 자체가 불가 → fail-closed(설계 §9).
        fadedCandidate("SMCI", "US", LocalDateTime.now().minusDays(10));
        givenPriceWindows("SMCI", List.of(), 2.0);
        when(valuationClient.getValuation("SMCI", "US")).thenReturn(new Valuation(SMCI_PER, SMCI_PBR));

        service.refresh();

        verify(candidateMapper).deactivate("SMCI");
    }

    @Test
    void index_return_fetch_failure_deactivates_faded_candidate() {
        // 2026-10-05 신규: 야후 차트 API 실패(null) → fail-closed(설계 §9), 기존 SPY 데이터
        // 부재 문제의 대체 경로 자체가 또 실패하는 경우도 안전하게 처리되는지 확인.
        fadedCandidate("SMCI", "US", LocalDateTime.now().minusDays(10));
        givenPriceWindows("SMCI", window(36.74, 41.15), null);
        when(valuationClient.getValuation("SMCI", "US")).thenReturn(new Valuation(SMCI_PER, SMCI_PBR));

        service.refresh();

        verify(candidateMapper).deactivate("SMCI");
    }

    @Test
    void same_theme_as_todays_stop_exit_is_detected() {
        // #840 — 066570 실사례 재현: "전자부품" 테마로 샀다가 오늘 손절, 같은 "전자부품" 테마의
        // 다른 헤드라인으로 재진입 시도 → 같은 스토리로 판정.
        AutoTradePosition stopped = stoppedPosition("066570", LocalDateTime.now().minusHours(20),
                LocalDateTime.now().minusMinutes(10));
        when(positionMapper.findLastStopExited("066570")).thenReturn(stopped);
        when(newsMapper.forSymbolBetween(eq("066570"), any(), any()))
                .thenReturn(List.of(newsWith("066570:S5,전자부품:S4")));
        when(newsMapper.active("066570", 5)).thenReturn(List.of(newsWith("066570:S5,전자부품:S4")));

        assertThat(service.isSameThemeAsRecentStopExit("066570", "KR")).isTrue();
    }

    @Test
    void different_theme_after_stop_exit_is_not_blocked() {
        AutoTradePosition stopped = stoppedPosition("066570", LocalDateTime.now().minusHours(20),
                LocalDateTime.now().minusMinutes(10));
        when(positionMapper.findLastStopExited("066570")).thenReturn(stopped);
        when(newsMapper.forSymbolBetween(eq("066570"), any(), any()))
                .thenReturn(List.of(newsWith("066570:S5,전자부품:S4")));
        when(newsMapper.active("066570", 5)).thenReturn(List.of(newsWith("066570:S5,자동차:S4")));

        assertThat(service.isSameThemeAsRecentStopExit("066570", "KR")).isFalse();
    }

    @Test
    void stop_exit_from_a_prior_day_is_not_compared() {
        AutoTradePosition stopped = stoppedPosition("066570", LocalDateTime.now().minusDays(1).minusHours(20),
                LocalDateTime.now().minusDays(1));
        when(positionMapper.findLastStopExited("066570")).thenReturn(stopped);

        assertThat(service.isSameThemeAsRecentStopExit("066570", "KR")).isFalse();
        verify(newsMapper, never()).forSymbolBetween(any(), any(), any());
    }

    @Test
    void no_theme_tags_found_for_stopped_position_is_fail_open() {
        AutoTradePosition stopped = stoppedPosition("066570", LocalDateTime.now().minusHours(20),
                LocalDateTime.now().minusMinutes(10));
        when(positionMapper.findLastStopExited("066570")).thenReturn(stopped);
        when(newsMapper.forSymbolBetween(eq("066570"), any(), any())).thenReturn(List.of());

        assertThat(service.isSameThemeAsRecentStopExit("066570", "KR")).isFalse();
    }

    private static AutoTradePosition stoppedPosition(String symbol, LocalDateTime entryAt, LocalDateTime exitAt) {
        AutoTradePosition p = new AutoTradePosition();
        p.setSymbol(symbol);
        p.setEntryAt(entryAt);
        p.setExitAt(exitAt);
        p.setExitReason("TRAIL_STOP");
        return p;
    }

    private static StockNews newsWith(String sentiment) {
        StockNews n = new StockNews();
        n.setSentiment(sentiment);
        return n;
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
