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
import static org.mockito.Mockito.*;

/** #848 — status()가 보유종목(현재가 포함)/최근로그를 포함해 반환하는지 검증. */
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
        controller = new AutoTradeController(scheduler, discoveryService, stateMapper, positionMapper,
                candidateMapper, orderLogMapper, priceCache, stockInfoCache);
    }

    @Test
    void 보유종목에_현재가와_가격손익이_포함된다() {
        AutoTradePosition position = new AutoTradePosition();
        position.setSymbol("005930");
        position.setMarket("KR");
        position.setEntryPrice(BigDecimal.valueOf(70000));
        position.setEntryQty(BigDecimal.valueOf(10));
        position.setEntryAt(LocalDateTime.now());
        when(positionMapper.findHolding()).thenReturn(List.of(position));
        when(candidateMapper.findActive()).thenReturn(List.of());
        when(priceCache.get(List.of("005930")))
                .thenReturn(List.of(new TossPrice("005930", "72000", "KRW", null)));
        when(stockInfoCache.get(List.of("005930")))
                .thenReturn(List.of(new TossStock("005930", "삼성전자", "Samsung Electronics", "KR", null, null, null, "KRW")));
        when(orderLogMapper.findRecent(20)).thenReturn(List.of());

        Map<String, Object> result = controller.status();

        @SuppressWarnings("unchecked")
        List<HoldingPnlView> holdingsView = (List<HoldingPnlView>) result.get("holdingsView");
        assertThat(holdingsView).hasSize(1);
        assertThat(holdingsView.get(0).currentPrice()).isEqualTo(BigDecimal.valueOf(72000));
        assertThat(holdingsView.get(0).unrealizedPnl()).isEqualTo(BigDecimal.valueOf(20000));
        assertThat(holdingsView.get(0).name()).isEqualTo("삼성전자");
        assertThat(result.get("recentLogs")).isEqualTo(List.of());
        assertThat(result.get("holdings")).isEqualTo(List.of(position));
        @SuppressWarnings("unchecked")
        Map<String, String> symbolNames = (Map<String, String>) result.get("symbolNames");
        assertThat(symbolNames).containsEntry("005930", "삼성전자");
    }

    @Test
    void 시세조회_실패해도_응답이_깨지지_않고_현재가만_null() {
        AutoTradePosition position = new AutoTradePosition();
        position.setSymbol("005930");
        position.setMarket("KR");
        position.setEntryPrice(BigDecimal.valueOf(70000));
        position.setEntryQty(BigDecimal.valueOf(10));
        position.setEntryAt(LocalDateTime.now());
        when(positionMapper.findHolding()).thenReturn(List.of(position));
        when(candidateMapper.findActive()).thenReturn(List.of());
        when(priceCache.get(anyList())).thenThrow(new RuntimeException("HTTP 429"));
        when(orderLogMapper.findRecent(20)).thenReturn(List.of());

        Map<String, Object> result = controller.status();

        @SuppressWarnings("unchecked")
        List<HoldingPnlView> holdingsView = (List<HoldingPnlView>) result.get("holdingsView");
        assertThat(holdingsView).hasSize(1);
        assertThat(holdingsView.get(0).currentPrice()).isNull();
        assertThat(holdingsView.get(0).unrealizedPnl()).isNull();
    }
}
