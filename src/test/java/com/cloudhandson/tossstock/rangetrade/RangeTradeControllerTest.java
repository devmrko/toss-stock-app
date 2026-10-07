package com.cloudhandson.tossstock.rangetrade;

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

/** #848/#849 — status()가 보유종목(현재가 포함)/요약을, orderlog()가 페이지네이션을 반환하는지 검증. */
class RangeTradeControllerTest {

    private RangeTradeStateMapper stateMapper;
    private RangeTradePositionMapper positionMapper;
    private RangeTradeOrderLogMapper orderLogMapper;
    private PriceCache priceCache;
    private StockInfoCache stockInfoCache;
    private RangeTradeController controller;

    @BeforeEach
    void setUp() {
        stateMapper = mock(RangeTradeStateMapper.class);
        positionMapper = mock(RangeTradePositionMapper.class);
        orderLogMapper = mock(RangeTradeOrderLogMapper.class);
        priceCache = mock(PriceCache.class);
        stockInfoCache = mock(StockInfoCache.class);
        when(stockInfoCache.get(anyList())).thenReturn(List.of());
        when(positionMapper.countExited()).thenReturn(0);
        when(positionMapper.countWin()).thenReturn(0);
        when(positionMapper.countLoss()).thenReturn(0);
        when(positionMapper.realizedPnlTotal()).thenReturn(BigDecimal.ZERO);
        when(positionMapper.realizedFeesTotal()).thenReturn(BigDecimal.ZERO);
        controller = new RangeTradeController(stateMapper, positionMapper, orderLogMapper, priceCache, stockInfoCache);
    }

    @Test
    void 보유종목에_현재가와_가격손익이_포함된다() {
        RangeTradePosition position = new RangeTradePosition();
        position.setSymbol("001540");
        position.setMarket("KR");
        position.setEntryPrice(BigDecimal.valueOf(10000));
        position.setEntryQty(BigDecimal.valueOf(50));
        position.setEntryAt(LocalDateTime.now());
        when(positionMapper.findHolding()).thenReturn(List.of(position));
        when(priceCache.get(List.of("001540")))
                .thenReturn(List.of(new TossPrice("001540", "9800", "KRW", null)));
        when(stockInfoCache.get(List.of("001540")))
                .thenReturn(List.of(new TossStock("001540", "안국약품", "Ahnkook Pharmaceutical", "KR", null, null, null, "KRW")));

        Map<String, Object> result = controller.status();

        @SuppressWarnings("unchecked")
        List<RangeHoldingPnlView> holdingsView = (List<RangeHoldingPnlView>) result.get("holdingsView");
        assertThat(holdingsView).hasSize(1);
        assertThat(holdingsView.get(0).currentPrice()).isEqualTo(BigDecimal.valueOf(9800));
        assertThat(holdingsView.get(0).unrealizedPnl()).isEqualTo(BigDecimal.valueOf(-10000));
        assertThat(holdingsView.get(0).name()).isEqualTo("안국약품");
    }

    @Test
    void 시세조회_실패해도_응답이_깨지지_않고_현재가만_null() {
        RangeTradePosition position = new RangeTradePosition();
        position.setSymbol("001540");
        position.setMarket("KR");
        position.setEntryPrice(BigDecimal.valueOf(10000));
        position.setEntryQty(BigDecimal.valueOf(50));
        position.setEntryAt(LocalDateTime.now());
        when(positionMapper.findHolding()).thenReturn(List.of(position));
        when(priceCache.get(anyList())).thenThrow(new RuntimeException("HTTP 429"));

        Map<String, Object> result = controller.status();

        @SuppressWarnings("unchecked")
        List<RangeHoldingPnlView> holdingsView = (List<RangeHoldingPnlView>) result.get("holdingsView");
        assertThat(holdingsView).hasSize(1);
        assertThat(holdingsView.get(0).currentPrice()).isNull();
    }

    @Test
    void 요약에_매매건수_승패_실현손익_수수료가_포함된다() {
        when(positionMapper.findHolding()).thenReturn(List.of());
        when(positionMapper.countExited()).thenReturn(4);
        when(positionMapper.countWin()).thenReturn(3);
        when(positionMapper.countLoss()).thenReturn(1);
        when(positionMapper.realizedPnlTotal()).thenReturn(BigDecimal.valueOf(5000));
        when(positionMapper.realizedFeesTotal()).thenReturn(BigDecimal.valueOf(900));

        Map<String, Object> result = controller.status();

        @SuppressWarnings("unchecked")
        Map<String, Object> summary = (Map<String, Object>) result.get("summary");
        assertThat(summary.get("totalTrades")).isEqualTo(4);
        assertThat(summary.get("wins")).isEqualTo(3);
        assertThat(summary.get("losses")).isEqualTo(1);
        assertThat(summary.get("realizedPnl")).isEqualTo(BigDecimal.valueOf(5000));
        assertThat(summary.get("totalFees")).isEqualTo(BigDecimal.valueOf(900));
        assertThat(summary.get("netRealizedPnl")).isEqualTo(BigDecimal.valueOf(4100));
    }

    @Test
    void 주문로그_페이지네이션이_시간순으로_동작한다() {
        RangeTradeOrderLog log1 = new RangeTradeOrderLog();
        log1.setSymbol("001540");
        when(orderLogMapper.findPage(0, 20)).thenReturn(List.of(log1));
        when(orderLogMapper.countAll()).thenReturn(3);
        when(stockInfoCache.get(List.of("001540")))
                .thenReturn(List.of(new TossStock("001540", "안국약품", null, "KR", null, null, null, "KRW")));

        Map<String, Object> page = controller.orderlog(0, 20);

        assertThat(page.get("page")).isEqualTo(0);
        assertThat(page.get("totalElements")).isEqualTo(3);
        @SuppressWarnings("unchecked")
        List<RangeTradeOrderLog> content = (List<RangeTradeOrderLog>) page.get("content");
        assertThat(content).containsExactly(log1);
    }
}
