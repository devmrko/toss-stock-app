package com.cloudhandson.tossstock.autotrade;

import com.cloudhandson.tossstock.toss.PriceCache;
import com.cloudhandson.tossstock.toss.StockInfoCache;
import com.cloudhandson.tossstock.toss.dto.TossPrice;
import com.cloudhandson.tossstock.toss.dto.TossStock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
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
    private AutoTradeController controller;

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
        when(orderLogMapper.totalFees()).thenReturn(BigDecimal.ZERO);
        controller = new AutoTradeController(scheduler, discoveryService, stateMapper, positionMapper,
                candidateMapper, orderLogMapper, priceCache, stockInfoCache);
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
        when(orderLogMapper.totalFees()).thenReturn(BigDecimal.valueOf(3200));

        Map<String, Object> result = controller.status();

        @SuppressWarnings("unchecked")
        Map<String, Object> summary = (Map<String, Object>) result.get("summary");
        assertThat(summary.get("totalTrades")).isEqualTo(12);
        assertThat(summary.get("wins")).isEqualTo(7);
        assertThat(summary.get("losses")).isEqualTo(5);
        assertThat(summary.get("realizedPnl")).isEqualTo(BigDecimal.valueOf(-24950));
        assertThat(summary.get("totalFees")).isEqualTo(BigDecimal.valueOf(3200));
        assertThat(summary.get("netRealizedPnl")).isEqualTo(BigDecimal.valueOf(-28150));
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
}
