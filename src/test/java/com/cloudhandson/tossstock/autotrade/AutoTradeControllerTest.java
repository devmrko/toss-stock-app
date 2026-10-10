package com.cloudhandson.tossstock.autotrade;

import com.cloudhandson.tossstock.toss.PriceCache;
import com.cloudhandson.tossstock.toss.StockInfoCache;
import com.cloudhandson.tossstock.toss.dto.TossPrice;
import com.cloudhandson.tossstock.toss.dto.TossStock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** #848/#849 — status()가 보유종목(현재가·매수이유 포함)/요약을, orderlog()가 페이지네이션을 반환하는지 검증. */
class AutoTradeControllerTest {

    private AutoTradeScheduler scheduler;
    private CandidateDiscoveryService discoveryService;
    private AutoTradeStateMapper stateMapper;
    private AutoTradePositionMapper positionMapper;
    private AutoTradeCandidateMapper candidateMapper;
    private AutoTradeOrderLogMapper orderLogMapper;
    private PriceCache priceCache;
    private StockInfoCache stockInfoCache;
    private OrderExecutor orderExecutor;
    private MockedStatic<MarketHours> marketHours;
    private AutoTradeController controller;

    private static AutoTradeProperties props() {
        return new AutoTradeProperties(false, BigDecimal.valueOf(3_000_000), 5,
                BigDecimal.valueOf(600_000), 15.0, 10.0, 10.0, "", "0 * * * * *", 20, 1.0, 1.5,
                30.0, 3.0, 200.0, BigDecimal.valueOf(300_000_000), BigDecimal.valueOf(200_000),
                20, 2, 30, 60, 30, 6.0, 5, 15.0, 40, "KR", 7, 3.0, 300.0, 15.0, 10.0, "0 30 8 * * MON",
                new AutoTradeProperties.Gate(20, 100, 2.5, 20, 0.2));
    }

    @BeforeEach
    void setUp() {
        scheduler = mock(AutoTradeScheduler.class);
        discoveryService = mock(CandidateDiscoveryService.class);
        stateMapper = mock(AutoTradeStateMapper.class);
        positionMapper = mock(AutoTradePositionMapper.class);
        candidateMapper = mock(AutoTradeCandidateMapper.class);
        orderLogMapper = mock(AutoTradeOrderLogMapper.class);
        priceCache = mock(PriceCache.class);
        stockInfoCache = mock(StockInfoCache.class);
        when(stockInfoCache.get(anyList())).thenReturn(List.of());
        when(positionMapper.countExited()).thenReturn(0);
        when(positionMapper.countWin()).thenReturn(0);
        when(positionMapper.countLoss()).thenReturn(0);
        when(positionMapper.realizedPnlTotal()).thenReturn(BigDecimal.ZERO);
        when(positionMapper.realizedFeesTotal()).thenReturn(BigDecimal.ZERO);
        orderExecutor = mock(OrderExecutor.class);
        controller = new AutoTradeController(scheduler, discoveryService, stateMapper, positionMapper,
                candidateMapper, orderLogMapper, priceCache, stockInfoCache, orderExecutor, props());
        marketHours = mockStatic(MarketHours.class);
        marketHours.when(() -> MarketHours.isOpen(anyString(), any(LocalDateTime.class))).thenReturn(true);
    }

    @AfterEach
    void tearDown() {
        marketHours.close();
    }

    private static AutoTradePosition position() {
        AutoTradePosition position = new AutoTradePosition();
        position.setSymbol("005930");
        position.setMarket("KR");
        position.setEntryPrice(BigDecimal.valueOf(70000));
        position.setEntryQty(BigDecimal.valueOf(10));
        position.setEntryAt(LocalDateTime.now());
        return position;
    }

    @Test
    void 보유종목에_현재가와_가격손익과_매수이유가_포함된다() {
        AutoTradePosition position = position();
        when(positionMapper.findHolding()).thenReturn(List.of(position));
        when(candidateMapper.findActive()).thenReturn(List.of());
        when(priceCache.get(List.of("005930")))
                .thenReturn(List.of(new TossPrice("005930", "72000", "KRW", null)));
        when(stockInfoCache.get(List.of("005930")))
                .thenReturn(List.of(new TossStock("005930", "삼성전자", "Samsung Electronics", "KR", null, null, null, "KRW")));
        AutoTradeOrderLog buyLog = new AutoTradeOrderLog();
        buyLog.setMessage("PER11.6/PBR0.47(저평가) 뉴스:S5 \"호재\" | 실주문 체결");
        when(orderLogMapper.findLastBuySuccess("005930")).thenReturn(buyLog);

        Map<String, Object> result = controller.status();

        @SuppressWarnings("unchecked")
        List<HoldingPnlView> holdingsView = (List<HoldingPnlView>) result.get("holdingsView");
        assertThat(holdingsView).hasSize(1);
        assertThat(holdingsView.get(0).currentPrice()).isEqualTo(BigDecimal.valueOf(72000));
        assertThat(holdingsView.get(0).unrealizedPnl()).isEqualTo(BigDecimal.valueOf(20000));
        assertThat(holdingsView.get(0).name()).isEqualTo("삼성전자");
        assertThat(holdingsView.get(0).buyReason()).contains("호재");
        assertThat(result.get("holdings")).isEqualTo(List.of(position));
        @SuppressWarnings("unchecked")
        Map<String, String> symbolNames = (Map<String, String>) result.get("symbolNames");
        assertThat(symbolNames).containsEntry("005930", "삼성전자");
    }

    @Test
    void 시세조회_실패해도_응답이_깨지지_않고_현재가만_null() {
        AutoTradePosition position = position();
        when(positionMapper.findHolding()).thenReturn(List.of(position));
        when(candidateMapper.findActive()).thenReturn(List.of());
        when(priceCache.get(anyList())).thenThrow(new RuntimeException("HTTP 429"));
        when(orderLogMapper.findLastBuySuccess(anyString())).thenReturn(null);

        Map<String, Object> result = controller.status();

        @SuppressWarnings("unchecked")
        List<HoldingPnlView> holdingsView = (List<HoldingPnlView>) result.get("holdingsView");
        assertThat(holdingsView).hasSize(1);
        assertThat(holdingsView.get(0).currentPrice()).isNull();
        assertThat(holdingsView.get(0).unrealizedPnl()).isNull();
        assertThat(holdingsView.get(0).buyReason()).isNull();
    }

    @Test
    void 요약에_매매건수_승패_실현손익_수수료가_포함된다() {
        when(positionMapper.findHolding()).thenReturn(List.of());
        when(candidateMapper.findActive()).thenReturn(List.of());
        when(positionMapper.countExited()).thenReturn(12);
        when(positionMapper.countWin()).thenReturn(7);
        when(positionMapper.countLoss()).thenReturn(5);
        when(positionMapper.realizedPnlTotal()).thenReturn(BigDecimal.valueOf(-24950));
        when(positionMapper.realizedFeesTotal()).thenReturn(BigDecimal.valueOf(3200));
        DailyRealizedPnl daily = new DailyRealizedPnl("2026-10-07", 12, BigDecimal.valueOf(-24950),
                BigDecimal.valueOf(3200), BigDecimal.valueOf(-28150));
        when(positionMapper.dailyRealizedSummary()).thenReturn(List.of(daily));

        Map<String, Object> result = controller.status();

        @SuppressWarnings("unchecked")
        Map<String, Object> summary = (Map<String, Object>) result.get("summary");
        assertThat(summary.get("totalTrades")).isEqualTo(12);
        assertThat(summary.get("wins")).isEqualTo(7);
        assertThat(summary.get("losses")).isEqualTo(5);
        assertThat(summary.get("realizedPnl")).isEqualTo(BigDecimal.valueOf(-24950));
        assertThat(summary.get("totalFees")).isEqualTo(BigDecimal.valueOf(3200));
        assertThat(summary.get("netRealizedPnl")).isEqualTo(BigDecimal.valueOf(-28150));
        @SuppressWarnings("unchecked")
        List<DailyRealizedPnl> dailyBreakdown = (List<DailyRealizedPnl>) summary.get("dailyBreakdown");
        assertThat(dailyBreakdown).containsExactly(daily);
    }

    @Test
    void 주문로그_페이지네이션이_시간순으로_동작한다() {
        AutoTradeOrderLog log1 = new AutoTradeOrderLog();
        log1.setSymbol("005930");
        when(orderLogMapper.findPage(20, 20)).thenReturn(List.of(log1));
        when(orderLogMapper.countAll()).thenReturn(45);
        when(stockInfoCache.get(List.of("005930")))
                .thenReturn(List.of(new TossStock("005930", "삼성전자", null, "KR", null, null, null, "KRW")));

        Map<String, Object> page = controller.orderlog(1, 20);

        assertThat(page.get("page")).isEqualTo(1);
        assertThat(page.get("size")).isEqualTo(20);
        assertThat(page.get("totalElements")).isEqualTo(45);
        @SuppressWarnings("unchecked")
        List<AutoTradeOrderLog> content = (List<AutoTradeOrderLog>) page.get("content");
        assertThat(content).containsExactly(log1);
        @SuppressWarnings("unchecked")
        Map<String, String> names = (Map<String, String>) page.get("symbolNames");
        assertThat(names).containsEntry("005930", "삼성전자");
    }

    // ---- #875 수동 정리 매도 ----

    @Test
    void 보유종목을_MANUAL로_매도한다() {
        // 인수조건 1
        when(positionMapper.findHolding()).thenReturn(List.of(position()));
        when(priceCache.get(List.of("005930")))
                .thenReturn(List.of(new TossPrice("005930", "72000", "KRW", null)));
        when(orderExecutor.sell(any(), eq(ExitReason.MANUAL), any(), anyString())).thenReturn(true);

        var res = controller.sellPosition("005930");

        assertThat(res.getStatusCode().value()).isEqualTo(200);
        assertThat(res.getBody()).containsEntry("sold", true);
        verify(orderExecutor).sell(any(), eq(ExitReason.MANUAL), any(), anyString());
    }

    @Test
    void 사유에_수동정리와_지시맥락이_남는다() {
        // 인수조건 5 — 사후에 자동매도와 구분돼야 한다.
        when(positionMapper.findHolding()).thenReturn(List.of(position()));
        when(priceCache.get(List.of("005930")))
                .thenReturn(List.of(new TossPrice("005930", "72000", "KRW", null)));
        when(orderExecutor.sell(any(), any(), any(), anyString())).thenReturn(true);

        controller.sellPosition("005930");

        org.mockito.ArgumentCaptor<String> r = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(orderExecutor).sell(any(), eq(ExitReason.MANUAL), any(), r.capture());
        assertThat(r.getValue()).startsWith("수동매도(")
                .contains("사유:사용자 지시 — 매수 근거 부실");
    }

    @Test
    void 보유하지_않은_종목이면_404이고_주문하지_않는다() {
        // 인수조건 2
        when(positionMapper.findHolding()).thenReturn(List.of(position()));

        var res = controller.sellPosition("000660");

        assertThat(res.getStatusCode().value()).isEqualTo(404);
        verify(orderExecutor, never()).sell(any(), any(), any(), anyString());
    }

    @Test
    void 장_마감_중이면_409이고_주문하지_않는다() {
        // 인수조건 3 — 가장 중요한 가드. sell 은 시장가라 닫힌 시장에 넣으면
        // 거부되거나 다음 개장 갭 가격에 체결된다.
        marketHours.when(() -> MarketHours.isOpen(anyString(), any(LocalDateTime.class))).thenReturn(false);
        when(positionMapper.findHolding()).thenReturn(List.of(position()));

        var res = controller.sellPosition("005930");

        assertThat(res.getStatusCode().value()).isEqualTo(409);
        verify(orderExecutor, never()).sell(any(), any(), any(), anyString());
        verify(priceCache, never()).get(anyList());
    }

    @Test
    void 현재가_조회_실패면_503이고_주문하지_않는다() {
        when(positionMapper.findHolding()).thenReturn(List.of(position()));
        when(priceCache.get(List.of("005930"))).thenReturn(List.of());

        var res = controller.sellPosition("005930");

        assertThat(res.getStatusCode().value()).isEqualTo(503);
        verify(orderExecutor, never()).sell(any(), any(), any(), anyString());
    }

    @Test
    void 주문_실패면_502를_반환한다() {
        when(positionMapper.findHolding()).thenReturn(List.of(position()));
        when(priceCache.get(List.of("005930")))
                .thenReturn(List.of(new TossPrice("005930", "72000", "KRW", null)));
        when(orderExecutor.sell(any(), any(), any(), anyString())).thenReturn(false);

        var res = controller.sellPosition("005930");

        assertThat(res.getStatusCode().value()).isEqualTo(502);
        assertThat(res.getBody()).containsEntry("sold", false);
    }

    // ---- #900 수동 매수 엔드포인트 ----

    private void givenReadyToBuy() {
        AutoTradeState st = new AutoTradeState();
        st.setCircuitBreakerTripped(false);
        when(stateMapper.find()).thenReturn(st);
        when(positionMapper.findHolding()).thenReturn(List.of());
        when(positionMapper.countHolding()).thenReturn(0);
        when(priceCache.get(List.of("CCL")))
                .thenReturn(List.of(new TossPrice("CCL", "26.13", "USD", null)));
    }

    @Test
    void 수동매수_confirm이_틀리면_400이고_주문하지_않는다() {
        // AC1 — 실주문 엔드포인트의 유일한 오타 방어선.
        var r = controller.manualBuy("CCL", BigDecimal.valueOf(36_000), "oops");

        assertThat(r.getStatusCode().value()).isEqualTo(400);
        verify(orderExecutor, never()).buy(anyString(), anyString(), any(), any(), anyString());
    }

    @Test
    void 수동매수_예산이_슬롯상한을_넘으면_400() {
        // AC2 — 상한 600,000
        var r = controller.manualBuy("CCL", BigDecimal.valueOf(600_001), "BUY-REAL");

        assertThat(r.getStatusCode().value()).isEqualTo(400);
        verify(orderExecutor, never()).buy(anyString(), anyString(), any(), any(), anyString());
    }

    @Test
    void 수동매수_예산이_0이하면_400() {
        // AC3
        assertThat(controller.manualBuy("CCL", BigDecimal.ZERO, "BUY-REAL")
                .getStatusCode().value()).isEqualTo(400);
        assertThat(controller.manualBuy("CCL", BigDecimal.valueOf(-1), "BUY-REAL")
                .getStatusCode().value()).isEqualTo(400);
        verify(orderExecutor, never()).buy(anyString(), anyString(), any(), any(), anyString());
    }

    @Test
    void 수동매수_symbol이_비면_400() {
        assertThat(controller.manualBuy("   ", BigDecimal.valueOf(36_000), "BUY-REAL")
                .getStatusCode().value()).isEqualTo(400);
    }

    @Test
    void 수동매수_서킷브레이커면_409() {
        // AC7
        AutoTradeState st = new AutoTradeState();
        st.setCircuitBreakerTripped(true);
        when(stateMapper.find()).thenReturn(st);

        var r = controller.manualBuy("CCL", BigDecimal.valueOf(36_000), "BUY-REAL");

        assertThat(r.getStatusCode().value()).isEqualTo(409);
        verify(orderExecutor, never()).buy(anyString(), anyString(), any(), any(), anyString());
    }

    @Test
    void 수동매수_장_마감이면_409() {
        // AC4 — 시장가 주문을 닫힌 시장에 넣으면 체결가를 통제할 수 없다.
        givenReadyToBuy();
        marketHours.when(() -> MarketHours.isOpen(anyString(), any(LocalDateTime.class))).thenReturn(false);

        var r = controller.manualBuy("CCL", BigDecimal.valueOf(36_000), "BUY-REAL");

        assertThat(r.getStatusCode().value()).isEqualTo(409);
        verify(orderExecutor, never()).buy(anyString(), anyString(), any(), any(), anyString());
    }

    @Test
    void 수동매수_이미_보유면_409() {
        // AC5
        AutoTradeState st = new AutoTradeState();
        when(stateMapper.find()).thenReturn(st);
        AutoTradePosition held = new AutoTradePosition();
        held.setSymbol("CCL");
        held.setMarket("US");
        when(positionMapper.findHolding()).thenReturn(List.of(held));

        var r = controller.manualBuy("CCL", BigDecimal.valueOf(36_000), "BUY-REAL");

        assertThat(r.getStatusCode().value()).isEqualTo(409);
        verify(orderExecutor, never()).buy(anyString(), anyString(), any(), any(), anyString());
    }

    @Test
    void 수동매수_슬롯이_없으면_409() {
        // AC6
        AutoTradeState st = new AutoTradeState();
        when(stateMapper.find()).thenReturn(st);
        when(positionMapper.findHolding()).thenReturn(List.of());
        when(positionMapper.countHolding()).thenReturn(5);   // maxSymbols=5

        var r = controller.manualBuy("CCL", BigDecimal.valueOf(36_000), "BUY-REAL");

        assertThat(r.getStatusCode().value()).isEqualTo(409);
        verify(orderExecutor, never()).buy(anyString(), anyString(), any(), any(), anyString());
    }

    @Test
    void 수동매수_시세조회_실패면_503() {
        // AC8
        AutoTradeState st = new AutoTradeState();
        when(stateMapper.find()).thenReturn(st);
        when(positionMapper.findHolding()).thenReturn(List.of());
        when(positionMapper.countHolding()).thenReturn(0);
        when(priceCache.get(List.of("CCL"))).thenReturn(List.of());

        var r = controller.manualBuy("CCL", BigDecimal.valueOf(36_000), "BUY-REAL");

        assertThat(r.getStatusCode().value()).isEqualTo(503);
        verify(orderExecutor, never()).buy(anyString(), anyString(), any(), any(), anyString());
    }

    @Test
    void 수동매수_정상이면_예산을_그대로_넘기고_수량은_지정하지_않는다() {
        // AC9 — 엔드포인트는 수량을 계산하지 않는다. OrderSizer(#886)가 계산해야 검증이 된다.
        givenReadyToBuy();
        when(orderExecutor.buy(eq("CCL"), eq("US"), any(), any(), anyString())).thenReturn(true);

        var r = controller.manualBuy("CCL", BigDecimal.valueOf(36_000), "BUY-REAL");

        assertThat(r.getStatusCode().value()).isEqualTo(200);
        verify(orderExecutor).buy(eq("CCL"), eq("US"),
                argThat(b -> b.compareTo(BigDecimal.valueOf(36_000)) == 0),
                argThat(p -> p.compareTo(new BigDecimal("26.13")) == 0), anyString());
    }

    @Test
    void 수동매수_주문이_실패하면_502() {
        // AC10 — 환율 조회 실패·수량 0·주문 거부가 전부 여기로 떨어진다.
        givenReadyToBuy();
        when(orderExecutor.buy(anyString(), anyString(), any(), any(), anyString())).thenReturn(false);

        var r = controller.manualBuy("CCL", BigDecimal.valueOf(36_000), "BUY-REAL");

        assertThat(r.getStatusCode().value()).isEqualTo(502);
        assertThat(r.getBody()).containsEntry("bought", false);
        assertThat(r.getBody().get("hint").toString()).contains("orderlog");
    }

    @Test
    void 수동매수_kr종목은_market_KR로_넘어간다() {
        AutoTradeState st = new AutoTradeState();
        when(stateMapper.find()).thenReturn(st);
        when(positionMapper.findHolding()).thenReturn(List.of());
        when(positionMapper.countHolding()).thenReturn(0);
        when(priceCache.get(List.of("005930")))
                .thenReturn(List.of(new TossPrice("005930", "263500", "KRW", null)));
        when(orderExecutor.buy(anyString(), anyString(), any(), any(), anyString())).thenReturn(true);

        controller.manualBuy("005930", BigDecimal.valueOf(600_000), "BUY-REAL");

        verify(orderExecutor).buy(eq("005930"), eq("KR"), any(), any(), anyString());
    }
}
