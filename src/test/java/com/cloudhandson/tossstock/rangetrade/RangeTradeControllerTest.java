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

/** #848 — status()가 보유종목(현재가 포함)/최근로그를 포함해 반환하는지 검증(#808 동일 테스트 복제). */
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
        when(orderLogMapper.findRecent(20)).thenReturn(List.of());

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
        when(orderLogMapper.findRecent(20)).thenReturn(List.of());

        Map<String, Object> result = controller.status();

        @SuppressWarnings("unchecked")
        List<RangeHoldingPnlView> holdingsView = (List<RangeHoldingPnlView>) result.get("holdingsView");
        assertThat(holdingsView).hasSize(1);
        assertThat(holdingsView.get(0).currentPrice()).isNull();
    }
}
