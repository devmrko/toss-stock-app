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
    private ValuationClient valuationClient;
    private CapitalReturnCatalystDetector catalystDetector;
    private UniverseMapper universeMapper;
    private CandidateDiscoveryService candidateDiscovery;
    private OrderExecutor orderExecutor;
    private AutoTradeScheduler scheduler;
    private RiskEventDetector riskEventDetector;
    private IndexLagAlertService indexLagAlert;
    private MockedStatic<MarketHours> marketHours;

    /** 운영 yml 과 같은 형태. 펀더멘털 통과 기준만 1로 낮춰(재무데이터 없는 환경) 매수 경로를 끝까지 태운다. */
    private static AutoTradeProperties props() {
        return new AutoTradeProperties(true, BigDecimal.valueOf(5_000_000), 5,
                BigDecimal.valueOf(1_000_000), 15.0, 10.0, 10.0, "", "0 */5 9-15 * * MON-FRI",
                20, 1.5, 2.0, 20.0, 2.0, 200.0, BigDecimal.valueOf(500_000_000),
                BigDecimal.valueOf(350_000), 20, 1, 30, 60, 30, 6.0, 5, 15.0, 40, "KR", 7, 3.0, 300.0, 15.0, 10.0, "0 30 8 * * MON", new AutoTradeProperties.Gate(35, 100, 2.5, 20, 0.2));
    }

    @BeforeEach
    void setUp() {
        stateMapper = mock(AutoTradeStateMapper.class);
        positionMapper = mock(AutoTradePositionMapper.class);
        candidateMapper = mock(AutoTradeCandidateMapper.class);
        dailyMapper = mock(DailyOhlcvMapper.class);
        newsMapper = mock(StockNewsMapper.class);
        priceCache = mock(PriceCache.class);
        riskEventDetector = mock(RiskEventDetector.class);
        indexLagAlert = mock(IndexLagAlertService.class);
        valuationClient = mock(ValuationClient.class);
        catalystDetector = mock(CapitalReturnCatalystDetector.class);
        universeMapper = mock(UniverseMapper.class);
        candidateDiscovery = mock(CandidateDiscoveryService.class);
        orderExecutor = mock(OrderExecutor.class);
        scheduler = new AutoTradeScheduler(props(), stateMapper, positionMapper, candidateMapper, dailyMapper,
                newsMapper, priceCache, riskEventDetector, valuationClient,
                catalystDetector, universeMapper,
                candidateDiscovery, orderExecutor, mock(DiscordClient.class), indexLagAlert);
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

    /** 진입 200,000 / 피크 235,000 보유 포지션. 손절선은 하드 180,000 · 트레일 211,500. */
    private AutoTradePosition holding() {
        AutoTradePosition p = new AutoTradePosition();
        p.setId(1L);
        p.setSymbol(SYMBOL);
        p.setMarket("KR");
        p.setEntryPrice(BigDecimal.valueOf(200_000));
        p.setEntryQty(BigDecimal.valueOf(5));
        p.setPeakPrice(BigDecimal.valueOf(235_000));
        p.setDryRun(true);
        return p;
    }

    private void holdingAt(String currentPrice) {
        when(stateMapper.find()).thenReturn(state());
        when(positionMapper.findHolding()).thenReturn(List.of(holding()));
        when(positionMapper.countHolding()).thenReturn(1);
        when(priceCache.get(List.of(SYMBOL)))
                .thenReturn(List.of(new TossPrice(SYMBOL, currentPrice, "KRW", null)));
    }

    // ---- #871 뉴스 만료는 매도 사유가 아니다 ----

    @Test
    void 뉴스가_식어도_손절선_위면_팔지_않는다() {
        // 인수조건 1 — 기존엔 NEWS_FADED 로 팔았다. 투자원칙 §4 의 매도 규칙은
        // 진입가 -10%, 고점 -10% 추적, 지수 열위 교체 셋뿐이고 "뉴스 만료"는 없다.
        holdingAt("225000");   // 하드 180,000 · 트레일 211,500 모두 위

        scheduler.tick();

        verify(orderExecutor, org.mockito.Mockito.never()).sell(any(), any(), any(), any());
    }

    @Test
    void 매수_매도_경로가_뉴스소멸_판정에_의존하지_않는다() {
        // #871(매도)·#887(매수) 회귀 방지를 구조로 고정한다. 예전엔 "조회하지 않는다"를
        // 목 검증으로 확인했지만, #887 에서 의존성 자체를 끊었으므로 그 목 검증은 항상
        // 참이 되어 회귀를 못 잡는다. 주입 여부를 직접 본다 — 다시 주입되면 여기서 깨진다.
        assertThat(AutoTradeScheduler.class.getDeclaredConstructors()[0].getParameterTypes())
                .doesNotContain(NewsFadeDetector.class);
    }

    @Test
    void 뉴스가_식었어도_하드손절_조건이면_HARD_STOP으로_팔린다() {
        // 인수조건 2 — 하방은 원칙이 정한 손절이 지킨다. 사유가 뉴스소멸이 아니어야 한다.
        // 피크=진입가(상승 이력 없음)여야 하드손절선(180,000)이 바인딩이 된다 —
        // 피크가 더 높으면 추적손절선이 위로 올라와 TRAIL_STOP 이 먼저 걸린다.
        AutoTradePosition p = holding();
        p.setPeakPrice(BigDecimal.valueOf(200_000));
        when(stateMapper.find()).thenReturn(state());
        when(positionMapper.findHolding()).thenReturn(List.of(p));
        when(positionMapper.countHolding()).thenReturn(1);
        when(priceCache.get(List.of(SYMBOL)))
                .thenReturn(List.of(new TossPrice(SYMBOL, "179000", "KRW", null)));

        scheduler.tick();

        verify(orderExecutor).sell(any(), eq(ExitReason.HARD_STOP), any(), any());
    }

    @Test
    void 뉴스가_식었어도_추적손절_조건이면_TRAIL_STOP으로_팔린다() {
        holdingAt("210500");   // 트레일손절선 211,500 이탈

        scheduler.tick();

        verify(orderExecutor).sell(any(), eq(ExitReason.TRAIL_STOP), any(), any());
    }

    // ---- #872 논거 무효는 매도 사유다 ----

    @Test
    void 진입_이후_리스크_기사가_나오면_RISK_EVENT로_팔린다() {
        // 인수조건 1 — 손절선 위여도 논거가 깨졌으면 즉시 이탈한다(원칙 §3-3/§3-5).
        holdingAt("225000");
        when(riskEventDetector.detect(eq(SYMBOL), any()))
                .thenReturn(new RiskVerdict("DILUTION", "삼성전자, 1200억 유상증자 결정"));

        scheduler.tick();

        verify(orderExecutor).sell(any(), eq(ExitReason.RISK_EVENT), any(), any());
    }

    @Test
    void 리스크_기사가_없으면_팔지_않는다() {
        // 인수조건 4 — #871 회귀 방지. detect 가 null 이면 매도 없음.
        holdingAt("225000");
        when(riskEventDetector.detect(eq(SYMBOL), any())).thenReturn(null);

        scheduler.tick();

        verify(orderExecutor, org.mockito.Mockito.never()).sell(any(), any(), any(), any());
    }

    @Test
    void 손절조건이_동시면_손절이_우선한다() {
        // 인수조건 5 — 원칙 §4 의 필수 하드룰이 먼저다. detect 는 호출조차 되지 않는다.
        AutoTradePosition p = holding();
        p.setPeakPrice(BigDecimal.valueOf(200_000));
        when(stateMapper.find()).thenReturn(state());
        when(positionMapper.findHolding()).thenReturn(List.of(p));
        when(positionMapper.countHolding()).thenReturn(1);
        when(priceCache.get(List.of(SYMBOL)))
                .thenReturn(List.of(new TossPrice(SYMBOL, "179000", "KRW", null)));
        when(riskEventDetector.detect(eq(SYMBOL), any()))
                .thenReturn(new RiskVerdict("DILUTION", "유상증자 결정"));

        scheduler.tick();

        verify(orderExecutor).sell(any(), eq(ExitReason.HARD_STOP), any(), any());
        verify(riskEventDetector, org.mockito.Mockito.never()).detect(any(), any());
    }

    @Test
    void RISK_EVENT_사유에_리스크종류와_기사제목이_남는다() {
        // 인수조건 6 — 사후 검증의 핵심이라 사유 문자열에 남긴다.
        holdingAt("225000");
        when(riskEventDetector.detect(eq(SYMBOL), any()))
                .thenReturn(new RiskVerdict("GOVERNANCE", "삼성전자, 횡령 혐의 조사"));

        scheduler.tick();

        ArgumentCaptor<String> rationale = ArgumentCaptor.forClass(String.class);
        verify(orderExecutor).sell(any(), eq(ExitReason.RISK_EVENT), any(), rationale.capture());
        assertThat(rationale.getValue())
                .startsWith("리스크이벤트(")
                .contains("사유:GOVERNANCE \"삼성전자, 횡령 혐의 조사\"");
    }

    // ---- #873 원칙 §4 미구현 조항 ----

    @Test
    void 지수_열위는_매도를_유발하지_않고_알림만_간다() {
        // 인수조건 4 — 원칙이 손절은 "필수 하드룰"로, 이건 "교체 고려"로 쓴다.
        holdingAt("225000");

        scheduler.tick();

        verify(orderExecutor, org.mockito.Mockito.never()).sell(any(), any(), any(), any());
        verify(indexLagAlert).checkAndAlert(any());
    }

    @Test
    void 매도되는_포지션엔_교체고려_알림을_보내지_않는다() {
        // 어차피 파는 포지션에 "교체 고려"는 잡음이다.
        holdingAt("210500");   // 트레일손절선 이탈 → 매도

        scheduler.tick();

        verify(orderExecutor).sell(any(), eq(ExitReason.TRAIL_STOP), any(), any());
        verify(indexLagAlert, org.mockito.Mockito.never()).checkAndAlert(any());
    }

    @Test
    void 멀티배거_포지션은_완화된_추적선이_결정근거에_찍힌다() {
        // 인수조건 7 — 판정과 로그가 같은 손절선을 써야 사후 검증이 성립한다.
        // 진입 10,000 / 피크 40,000(+300%) → 완화폭 15% → 트레일선 34,000.
        AutoTradePosition p = holding();
        p.setEntryPrice(BigDecimal.valueOf(10_000));
        p.setPeakPrice(BigDecimal.valueOf(40_000));
        when(stateMapper.find()).thenReturn(state());
        when(positionMapper.findHolding()).thenReturn(List.of(p));
        when(positionMapper.countHolding()).thenReturn(1);
        when(priceCache.get(List.of(SYMBOL)))
                .thenReturn(List.of(new TossPrice(SYMBOL, "33000", "KRW", null)));

        scheduler.tick();

        ArgumentCaptor<String> rationale = ArgumentCaptor.forClass(String.class);
        verify(orderExecutor).sell(any(), eq(ExitReason.TRAIL_STOP), any(), rationale.capture());
        // 완화 전(10%)이면 36,000 이 찍혔을 자리
        assertThat(rationale.getValue()).contains("트레일34000");
    }

    @Test
    void 멀티배거_구간에선_기존_10퍼센트_손절선에_걸리지_않는다() {
        // 인수조건 5 — 피크 -12% 는 완화폭(15%) 안쪽이라 버틴다.
        AutoTradePosition p = holding();
        p.setEntryPrice(BigDecimal.valueOf(10_000));
        p.setPeakPrice(BigDecimal.valueOf(40_000));
        when(stateMapper.find()).thenReturn(state());
        when(positionMapper.findHolding()).thenReturn(List.of(p));
        when(positionMapper.countHolding()).thenReturn(1);
        when(priceCache.get(List.of(SYMBOL)))
                .thenReturn(List.of(new TossPrice(SYMBOL, "35200", "KRW", null)));   // 피크 -12%

        scheduler.tick();

        verify(orderExecutor, org.mockito.Mockito.never()).sell(any(), any(), any(), any());
    }

    @Test
    void NEWS_FADED_enum은_과거기록용으로_남아있다() {
        // 인수조건 3 — auto_trade_position.exit_reason 에 'NEWS_FADED' 문자열이 이미
        // 저장돼 있어(2026-10-08 4건 포함) enum 에서 빼면 과거 매매 조회가 깨진다.
        assertThat(ExitReason.valueOf("NEWS_FADED")).isEqualTo(ExitReason.NEWS_FADED);
        assertThat(SellRationale.describe(ExitReason.NEWS_FADED, BigDecimal.valueOf(200_000),
                BigDecimal.valueOf(235_000), BigDecimal.valueOf(225_000),
                BigDecimal.valueOf(180_000), BigDecimal.valueOf(211_500))).contains("뉴스소멸");
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
        when(dailyMapper.breadth(anyString(), anyInt())).thenReturn(Map.of("UP", 60L, "TOTAL", 100L)); // 시장 게이트 통과
        when(candidateMapper.findActive()).thenReturn(List.of(c));
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
        when(dailyMapper.breadth(anyString(), anyInt())).thenReturn(Map.of("UP", 60L, "TOTAL", 100L));
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
        // #838/#859: 최근 5일 저점 대비 +10%(임계값 6% 이상)인데 실적직결 촉매가 없으면
        // 저평가/펀더멘털을 다 통과해도 그 틱엔 매수하지 않는다.
        AutoTradeCandidate c = new AutoTradeCandidate();
        c.setSymbol(SYMBOL);
        c.setMarket("KR");
        when(stateMapper.find()).thenReturn(state());
        when(positionMapper.findHolding()).thenReturn(List.of());
        when(positionMapper.countHolding()).thenReturn(0);
        when(dailyMapper.breadth(anyString(), anyInt())).thenReturn(Map.of("UP", 60L, "TOTAL", 100L));
        when(candidateMapper.findActive()).thenReturn(List.of(c));
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
        // 같은 +10%라도 실적직결 촉매(수주/계약 등)가 있으면 보류하지 않는다(066570류 사례).
        AutoTradeCandidate c = new AutoTradeCandidate();
        c.setSymbol(SYMBOL);
        c.setMarket("KR");
        when(stateMapper.find()).thenReturn(state());
        when(positionMapper.findHolding()).thenReturn(List.of());
        when(positionMapper.countHolding()).thenReturn(0);
        when(positionMapper.realizedPnlTotal()).thenReturn(BigDecimal.ZERO); // #845
        when(dailyMapper.breadth(anyString(), anyInt())).thenReturn(Map.of("UP", 60L, "TOTAL", 100L));
        when(candidateMapper.findActive()).thenReturn(List.of(c));
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
    void 어제_급등해서_오늘은_하락중이어도_저점대비_과열이면_보류() {
        // #859 회귀(안랩 2차 실사례): 10/2 78,400 → 10/6 90,300 급등 후, 10/7에 83,400으로
        // "전일 대비 -7.6%"라 기존 일봉 기준으론 그대로 통과했지만 저점(78,400) 대비로는
        // +6.4%로 여전히 과열 구간이었고 실제로 손실(-7.91%)로 이어졌다.
        AutoTradeCandidate c = new AutoTradeCandidate();
        c.setSymbol(SYMBOL);
        c.setMarket("KR");
        LocalDate d0 = LocalDate.now().minusDays(2);
        List<DailyOhlcv> bars = List.of(
                new DailyOhlcv(SYMBOL, d0, BigDecimal.valueOf(78_400), BigDecimal.valueOf(78_400),
                        BigDecimal.valueOf(78_400), BigDecimal.valueOf(78_400), 1_000_000L),
                new DailyOhlcv(SYMBOL, d0.plusDays(1), BigDecimal.valueOf(90_300), BigDecimal.valueOf(90_300),
                        BigDecimal.valueOf(90_300), BigDecimal.valueOf(90_300), 2_000_000L));
        when(stateMapper.find()).thenReturn(state());
        when(positionMapper.findHolding()).thenReturn(List.of());
        when(positionMapper.countHolding()).thenReturn(0);
        when(dailyMapper.breadth(anyString(), anyInt())).thenReturn(Map.of("UP", 60L, "TOTAL", 100L));
        when(candidateMapper.findActive()).thenReturn(List.of(c));
        when(dailyMapper.recentForSymbols(any(), any(LocalDate.class))).thenReturn(bars);
        when(valuationClient.getValuation(SYMBOL, "KR"))
                .thenReturn(new Valuation(BigDecimal.valueOf(11.6), BigDecimal.valueOf(0.47)));
        when(catalystDetector.hasRecentCatalyst(SYMBOL)).thenReturn(false);
        when(priceCache.get(List.of(SYMBOL)))
                .thenReturn(List.of(new TossPrice(SYMBOL, "83400", "KRW", null)));

        scheduler.tick();

        verify(orderExecutor, org.mockito.Mockito.never()).buy(any(), any(), any(), any(), any());
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
        when(dailyMapper.breadth(anyString(), anyInt())).thenReturn(Map.of("UP", 60L, "TOTAL", 100L));
        when(candidateMapper.findActive()).thenReturn(List.of(c));
        when(positionMapper.lastStopExitAt(SYMBOL)).thenReturn(LocalDateTime.now().minusSeconds(7));

        scheduler.tick();

        verify(orderExecutor, org.mockito.Mockito.never()).buy(any(), any(), any(), any(), any());
        // 손절 쿨다운 자체로 막히므로, 그 뒤 단계는 아예 평가되지 않아야 한다.
        // (#887 로 뉴스판정이 사라졌으므로 그 뒤 첫 외부호출인 밸류에이션으로 고정한다.)
        verify(valuationClient, org.mockito.Mockito.never()).getValuation(any(), any());
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
        when(dailyMapper.breadth(anyString(), anyInt())).thenReturn(Map.of("UP", 60L, "TOTAL", 100L));
        when(candidateMapper.findActive()).thenReturn(List.of(c));
        when(positionMapper.lastStopExitAt(SYMBOL)).thenReturn(LocalDateTime.now().minusMinutes(31));
        when(dailyMapper.recentForSymbols(any(), any(LocalDate.class))).thenReturn(priceMoveBars());
        when(valuationClient.getValuation(SYMBOL, "KR"))
                .thenReturn(new Valuation(BigDecimal.valueOf(11.6), BigDecimal.valueOf(0.47)));
        when(catalystDetector.hasRecentCatalyst(SYMBOL)).thenReturn(false);
        when(priceCache.get(List.of(SYMBOL)))
                .thenReturn(List.of(new TossPrice(SYMBOL, "102000", "KRW", null)));

        scheduler.tick();

        verify(orderExecutor).buy(eq(SYMBOL), eq("KR"), any(), any(), any());
    }

    // ---- #887 뉴스는 진입 트리거가 아니다 ----

    @Test
    void 뉴스가_전혀_없는_스크리너_후보도_매수_경로를_통과한다() {
        // 인수조건 AC5 — 스크리너 후보는 애초에 뉴스가 없다. 예전 NEWS_FADED 게이트를
        // 남겨 뒀다면 hasNewsFaded 가 항상 참이 되어 전 후보가 차단됐을 것이다.
        // 뉴스 관련 스텁을 하나도 주지 않는 것이 이 테스트의 핵심이다.
        AutoTradeCandidate c = new AutoTradeCandidate();
        c.setSymbol(SYMBOL);
        c.setMarket("KR");
        when(stateMapper.find()).thenReturn(state());
        when(positionMapper.findHolding()).thenReturn(List.of());
        when(positionMapper.countHolding()).thenReturn(0);
        when(positionMapper.realizedPnlTotal()).thenReturn(BigDecimal.ZERO);
        when(dailyMapper.breadth(anyString(), anyInt())).thenReturn(Map.of("UP", 60L, "TOTAL", 100L));
        when(candidateMapper.findActive()).thenReturn(List.of(c));
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
    void 급등률이_촉매면제_상한을_넘으면_촉매가_있어도_매수하지_않는다() {
        // 인수조건 AC6 — #855 는 촉매면제에 "밸류에이션" 상한만 걸었고 급등률은 통째로
        // 면제됐다. 촉매일 조건부 측정(N=2,149)에서 급등 +10~15% 구간의 하드손절률이 71%,
        // +25% 이상은 89.9% 다. 저점 100,000 대비 현재가 120,000 = +20% ≥ 상한 15.0.
        // 밸류에이션은 일부러 촉매 상한 안(PER 50 < 20x3, PBR 5 < 2x3)에 둬서,
        // 예전 코드라면 면제를 받아 매수됐을 조건임을 고정한다.
        stubCatalystBuyPath("120000");

        scheduler.tick();

        verify(orderExecutor, org.mockito.Mockito.never()).buy(any(), any(), any(), any(), any());
    }

    @Test
    void 급등률이_촉매면제_상한_미만이면_촉매로_면제된다() {
        // AC6 의 대칭 — 상한을 추가해도 기존 면제 경로가 살아 있어야 한다.
        // 저점 100,000 대비 현재가 110,000 = +10% < 상한 15.0 (단 기본 임계 6.0 은 초과).
        stubCatalystBuyPath("110000");

        scheduler.tick();

        verify(orderExecutor).buy(eq(SYMBOL), eq("KR"), any(), any(), any());
    }

    /** 촉매면제 경로를 끝까지 태우는 공통 스텁. 5일 저점 100,000, 저평가 아님, 촉매 있음. */
    private void stubCatalystBuyPath(String currentPrice) {
        AutoTradeCandidate c = new AutoTradeCandidate();
        c.setSymbol(SYMBOL);
        c.setMarket("KR");
        when(stateMapper.find()).thenReturn(state());
        when(positionMapper.findHolding()).thenReturn(List.of());
        when(positionMapper.countHolding()).thenReturn(0);
        when(positionMapper.realizedPnlTotal()).thenReturn(BigDecimal.ZERO);
        when(dailyMapper.breadth(anyString(), anyInt())).thenReturn(Map.of("UP", 60L, "TOTAL", 100L));
        when(candidateMapper.findActive()).thenReturn(List.of(c));
        when(dailyMapper.recentForSymbols(any(), any(LocalDate.class))).thenReturn(extremeMoveBars());
        when(valuationClient.getValuation(SYMBOL, "KR"))
                .thenReturn(new Valuation(BigDecimal.valueOf(50.0), BigDecimal.valueOf(5.0)));
        when(catalystDetector.hasRecentCatalyst(SYMBOL)).thenReturn(true);
        when(priceCache.get(List.of(SYMBOL)))
                .thenReturn(List.of(new TossPrice(SYMBOL, currentPrice, "KRW", null)));
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

    /** 5일 저점 100,000 → 현재가 110,000(+10.0%, #859 임계값 6% 초과). */
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

    // ---- #879 시장상황 게이트는 시장별로 ----

    @Test
    void KR_breadth가_낮아도_US_후보는_막히지_않는다() {
        // 인수조건 2 — 이 버그의 핵심. 코스피가 급락하면 그 이유로 미국 주식 매수가 막혔다.
        AutoTradeCandidate us = new AutoTradeCandidate();
        us.setSymbol("TSM");
        us.setMarket("US");
        when(stateMapper.find()).thenReturn(state());
        when(positionMapper.findHolding()).thenReturn(List.of());
        when(positionMapper.countHolding()).thenReturn(0);
        when(candidateMapper.findActive()).thenReturn(List.of(us));
        // KR 은 임계 미만(10%) — 예전이라면 scanCandidates 자체가 스킵됐다.
        // US 는 SPY 를 변동성 정규화해 판정한다(#883): 일간 +0.5%, 변동성 1.0% → 통과.
        when(dailyMapper.breadth(eq("KR"), anyInt())).thenReturn(Map.of("UP", 10L, "TOTAL", 3699L));
        when(valuationClient.getIndexRiskSignal(eq("SPY"), anyInt()))
                .thenReturn(new IndexRiskSignal(0.5, 1.0));
        when(dailyMapper.recentForSymbols(any(), any(LocalDate.class))).thenReturn(priceMoveBars());
        when(valuationClient.getValuation("TSM", "US"))
                .thenReturn(new Valuation(BigDecimal.valueOf(11.6), BigDecimal.valueOf(0.47)));
        when(priceCache.get(List.of("TSM")))
                .thenReturn(List.of(new TossPrice("TSM", "102000", "USD", null)));

        scheduler.tick();

        verify(valuationClient).getIndexRiskSignal(eq("SPY"), anyInt());
        verify(orderExecutor).buy(eq("TSM"), eq("US"), any(), any(), anyString());
    }

    @Test
    void KR_breadth가_낮으면_KR_후보는_막힌다() {
        // 인수조건 5 — KR 판정은 변경 전과 같아야 한다.
        AutoTradeCandidate kr = new AutoTradeCandidate();
        kr.setSymbol(SYMBOL);
        kr.setMarket("KR");
        when(stateMapper.find()).thenReturn(state());
        when(positionMapper.findHolding()).thenReturn(List.of());
        when(positionMapper.countHolding()).thenReturn(0);
        when(candidateMapper.findActive()).thenReturn(List.of(kr));
        when(dailyMapper.breadth(eq("KR"), anyInt())).thenReturn(Map.of("UP", 10L, "TOTAL", 3699L));

        scheduler.tick();

        verify(orderExecutor, org.mockito.Mockito.never()).buy(any(), any(), any(), any(), anyString());
    }

    @Test
    void 같은_시장_후보가_여러건이어도_breadth는_한번만_조회한다() {
        // 인수조건 6 — 틱 로컬 캐시.
        AutoTradeCandidate a = new AutoTradeCandidate();
        a.setSymbol("005930");
        a.setMarket("KR");
        AutoTradeCandidate b = new AutoTradeCandidate();
        b.setSymbol("000660");
        b.setMarket("KR");
        when(stateMapper.find()).thenReturn(state());
        when(positionMapper.findHolding()).thenReturn(List.of());
        when(positionMapper.countHolding()).thenReturn(0);
        when(candidateMapper.findActive()).thenReturn(List.of(a, b));
        when(dailyMapper.breadth(eq("KR"), anyInt())).thenReturn(Map.of("UP", 10L, "TOTAL", 3699L));

        scheduler.tick();

        verify(dailyMapper, org.mockito.Mockito.times(1)).breadth(eq("KR"), anyInt());
    }

    // ---- #883 US 레짐 임계는 변동성 정규화 ----

    private void usCandidate() {
        AutoTradeCandidate us = new AutoTradeCandidate();
        us.setSymbol("TSM");
        us.setMarket("US");
        when(stateMapper.find()).thenReturn(state());
        when(positionMapper.findHolding()).thenReturn(List.of());
        when(positionMapper.countHolding()).thenReturn(0);
        when(candidateMapper.findActive()).thenReturn(List.of(us));
        when(dailyMapper.recentForSymbols(any(), any(LocalDate.class))).thenReturn(priceMoveBars());
        when(valuationClient.getValuation("TSM", "US"))
                .thenReturn(new Valuation(BigDecimal.valueOf(11.6), BigDecimal.valueOf(0.47)));
        when(priceCache.get(List.of("TSM")))
                .thenReturn(List.of(new TossPrice("TSM", "102000", "USD", null)));
    }

    private void spySignal(double dailyPct, double volPct) {
        when(valuationClient.getIndexRiskSignal(eq("SPY"), anyInt()))
                .thenReturn(new IndexRiskSignal(dailyPct, volPct));
    }

    @Test
    void 변동성_대비_극단_하락이면_US_매수가_막힌다() {
        // 인수조건 1 — 변동성 1.0% x 2.5σ = 임계 -2.5%. 일간 -3.0% 는 그 미만.
        usCandidate();
        spySignal(-3.0, 1.0);

        scheduler.tick();

        verify(orderExecutor, org.mockito.Mockito.never()).buy(any(), any(), any(), any(), anyString());
    }

    @Test
    void 임계_안쪽_하락이면_US_매수가_진행된다() {
        usCandidate();
        spySignal(-2.0, 1.0);   // 임계 -2.5% 안쪽

        scheduler.tick();

        verify(orderExecutor).buy(eq("TSM"), eq("US"), any(), any(), anyString());
    }

    @Test
    void 고변동성기엔_같은_하락이_통과한다() {
        // 인수조건 2의 핵심 — 이게 고정 임계와 갈리는 지점이다.
        // 일간 -3.0% 는 변동성 1.0% 에서는 차단됐지만, 변동성 2.0% 에서는 임계가
        // -5.0% 로 느슨해져 통과한다. 고정 -1.5% 라면 두 경우 모두 차단이었다.
        usCandidate();
        spySignal(-3.0, 2.0);

        scheduler.tick();

        verify(orderExecutor).buy(eq("TSM"), eq("US"), any(), any(), anyString());
    }

    @Test
    void 변동성이_비정상적으로_작으면_하한이_적용된다() {
        // 인수조건 3 — 변동성 0.05% 면 임계가 -0.125% 로 붙어 아무 하락일이나 차단된다.
        // 하한 0.2% 가 적용돼 임계 -0.5% → 일간 -0.4% 는 통과.
        usCandidate();
        spySignal(-0.4, 0.05);

        scheduler.tick();

        verify(orderExecutor).buy(eq("TSM"), eq("US"), any(), any(), anyString());
    }

    @Test
    void SPY_신호가_없으면_US_매수는_통과시킨다() {
        // 인수조건 4
        usCandidate();
        when(valuationClient.getIndexRiskSignal(eq("SPY"), anyInt())).thenReturn(null);

        scheduler.tick();

        verify(orderExecutor).buy(eq("TSM"), eq("US"), any(), any(), anyString());
    }

    @Test
    void US_판정에_KR_breadth를_조회하지_않는다() {
        // 인수조건 6 — KR 판정은 변경되지 않고, 서로 섞이지 않는다.
        usCandidate();
        spySignal(0.5, 1.0);

        scheduler.tick();

        verify(dailyMapper, org.mockito.Mockito.never()).breadth(eq("KR"), anyInt());
    }

    // ---- #884 매수 스캔 탈락 사유 계측 ----

    private static java.util.Optional<ScanVerdict> stageOf(java.util.List<ScanVerdict> vs, String symbol) {
        return vs.stream().filter(v -> symbol.equals(v.symbol())).findFirst();
    }

    @Test
    void 인기_미달로_탈락하면_사유가_기록된다() {
        // 인수조건 1 — 이전에는 이 탈락이 완전히 무기록이었다.
        AutoTradeCandidate c = new AutoTradeCandidate();
        c.setSymbol(SYMBOL);
        c.setMarket("KR");
        when(stateMapper.find()).thenReturn(state());
        when(positionMapper.findHolding()).thenReturn(List.of());
        when(positionMapper.countHolding()).thenReturn(0);
        when(candidateMapper.findActive()).thenReturn(List.of(c));
        // breadth 54%(2000/3699) — 레짐 게이트를 통과시켜 인기 게이트까지 보낸다.
        // (처음엔 60/3699=1.6% 로 써서 REGIME 에서 막혔다 — 계측이 그 실수를 잡아줬다)
        when(dailyMapper.breadth(eq("KR"), anyInt())).thenReturn(Map.of("UP", 2000L, "TOTAL", 3699L));
        when(dailyMapper.recentForSymbols(any(), any(LocalDate.class))).thenReturn(List.of());

        scheduler.tick();

        assertThat(stageOf(scheduler.lastScan(), SYMBOL)).isPresent();
        assertThat(stageOf(scheduler.lastScan(), SYMBOL).get().stage()).isEqualTo("POPULARITY");
        assertThat(scheduler.lastScanAt()).isNotNull();
    }

    @Test
    void 장_마감이면_사유가_기록된다() {
        marketHours.when(() -> MarketHours.isOpen(anyString(), any(LocalDateTime.class))).thenReturn(false);
        AutoTradeCandidate c = new AutoTradeCandidate();
        c.setSymbol("TSM");
        c.setMarket("US");
        when(stateMapper.find()).thenReturn(state());
        when(positionMapper.findHolding()).thenReturn(List.of());
        when(positionMapper.countHolding()).thenReturn(0);
        when(candidateMapper.findActive()).thenReturn(List.of(c));

        scheduler.tick();

        assertThat(stageOf(scheduler.lastScan(), "TSM").get().stage()).isEqualTo("MARKET_CLOSED");
    }

    @Test
    void 매수_성공도_기록된다() {
        // 인수조건 2 — "왜 샀나"도 같은 자리에서 보여야 한다.
        usCandidate();
        spySignal(0.5, 1.0);
        when(orderExecutor.buy(any(), any(), any(), any(), anyString())).thenReturn(true);

        scheduler.tick();

        ScanVerdict v = stageOf(scheduler.lastScan(), "TSM").orElseThrow();
        assertThat(v.stage()).isEqualTo("BUY");
        assertThat(v.detail()).contains("PER");
    }

    @Test
    void 슬롯이_없으면_tick_사유가_기록된다() {
        // 인수조건 3 — scanCandidates 에 도달조차 못하는 경우가 "왜 안 샀나"의 답인 때가 많다.
        when(stateMapper.find()).thenReturn(state());
        when(positionMapper.findHolding()).thenReturn(List.of());
        when(positionMapper.countHolding()).thenReturn(5);   // max-symbols=5

        scheduler.tick();

        assertThat(scheduler.lastScan()).hasSize(1);
        assertThat(scheduler.lastScan().get(0).stage()).isEqualTo("NO_SLOT");
        assertThat(scheduler.lastScan().get(0).symbol()).isNull();
        verify(candidateMapper, org.mockito.Mockito.never()).findActive();
    }

    @Test
    void 후보가_없으면_사유가_기록된다() {
        when(stateMapper.find()).thenReturn(state());
        when(positionMapper.findHolding()).thenReturn(List.of());
        when(positionMapper.countHolding()).thenReturn(0);
        when(candidateMapper.findActive()).thenReturn(List.of());

        scheduler.tick();

        assertThat(scheduler.lastScan()).hasSize(1);
        assertThat(scheduler.lastScan().get(0).stage()).isEqualTo("NO_CANDIDATE");
    }

    @Test
    void 스냅샷은_틱마다_교체된다() {
        // 인수조건 4 — 누적하면 메모리가 무한히 자란다.
        when(stateMapper.find()).thenReturn(state());
        when(positionMapper.findHolding()).thenReturn(List.of());
        when(positionMapper.countHolding()).thenReturn(0);
        when(candidateMapper.findActive()).thenReturn(List.of());

        scheduler.tick();
        scheduler.tick();

        assertThat(scheduler.lastScan()).hasSize(1);   // 2건으로 늘지 않는다
    }
}
