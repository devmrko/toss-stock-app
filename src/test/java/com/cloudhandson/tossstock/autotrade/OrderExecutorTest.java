package com.cloudhandson.tossstock.autotrade;

import com.cloudhandson.tossstock.briefing.DiscordClient;
import com.cloudhandson.tossstock.toss.TossApiClient;
import com.cloudhandson.tossstock.toss.dto.TossOrder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * OrderExecutor 안전장치 검증 — 가장 중요한 테스트: dryRun 일 때 실주문이 절대 안 나가는지.
 * 설계: docs/design/808-auto-trade-engine/fn-order-executor.md
 */
class OrderExecutorTest {

    private AutoTradeProperties props;
    private AutoTradeStateMapper stateMapper;
    private AutoTradePositionMapper positionMapper;
    private AutoTradeOrderLogMapper logMapper;
    private TossApiClient toss;
    private DiscordClient discord;
    private OrderExecutor executor;

    @BeforeEach
    void setUp() {
        props = new AutoTradeProperties(true, BigDecimal.valueOf(5_000_000), 5,
                BigDecimal.valueOf(1_000_000), 15.0, 10.0, 10.0, "", "0 */5 9-15 * * MON-FRI", 20, 1.5, 20.0, 2.0, 200.0, BigDecimal.valueOf(500_000_000), 20, 4,
                new AutoTradeProperties.Gate(35, 1, 3));
        stateMapper = mock(AutoTradeStateMapper.class);
        positionMapper = mock(AutoTradePositionMapper.class);
        logMapper = mock(AutoTradeOrderLogMapper.class);
        toss = mock(TossApiClient.class);
        discord = mock(DiscordClient.class);
        executor = new OrderExecutor(props, stateMapper, positionMapper, logMapper, toss, discord);
    }

    private AutoTradeState stateWith(boolean dbDryRun) {
        AutoTradeState s = new AutoTradeState();
        s.setDryRun(dbDryRun);
        s.setTotalBudget(BigDecimal.valueOf(5_000_000));
        s.setPerSymbolBudget(BigDecimal.valueOf(1_000_000));
        return s;
    }

    @Test
    void global_dry_run_true_never_calls_place_order() {
        when(stateMapper.find()).thenReturn(stateWith(false)); // DB는 false 여도

        boolean ok = executor.buy("005930", "KR", BigDecimal.valueOf(1_000_000), BigDecimal.valueOf(50_000));

        assertThat(ok).isTrue();
        verify(toss, never()).placeOrder(any());
        verify(positionMapper).insert(argThat(p -> p.isDryRun()));
    }

    @Test
    void db_dry_run_true_also_blocks_real_order_even_if_config_false() {
        AutoTradeProperties liveProps = new AutoTradeProperties(false, BigDecimal.valueOf(5_000_000), 5,
                BigDecimal.valueOf(1_000_000), 15.0, 10.0, 10.0, "", "0 */5 9-15 * * MON-FRI", 20, 1.5, 20.0, 2.0, 200.0, BigDecimal.valueOf(500_000_000), 20, 4,
                new AutoTradeProperties.Gate(35, 1, 3));
        OrderExecutor liveExecutor = new OrderExecutor(liveProps, stateMapper, positionMapper, logMapper, toss, discord);
        when(stateMapper.find()).thenReturn(stateWith(true)); // DB가 true면 이중 안전장치로 드라이런

        liveExecutor.buy("005930", "KR", BigDecimal.valueOf(1_000_000), BigDecimal.valueOf(50_000));

        verify(toss, never()).placeOrder(any());
    }

    @Test
    void both_false_places_real_order_exactly_once() {
        AutoTradeProperties liveProps = new AutoTradeProperties(false, BigDecimal.valueOf(5_000_000), 5,
                BigDecimal.valueOf(1_000_000), 15.0, 10.0, 10.0, "", "0 */5 9-15 * * MON-FRI", 20, 1.5, 20.0, 2.0, 200.0, BigDecimal.valueOf(500_000_000), 20, 4,
                new AutoTradeProperties.Gate(35, 1, 3));
        OrderExecutor liveExecutor = new OrderExecutor(liveProps, stateMapper, positionMapper, logMapper, toss, discord);
        when(stateMapper.find()).thenReturn(stateWith(false));
        TossOrder.Execution exec = new TossOrder.Execution("20", "50000", "1000000", "0", "0", null, null);
        when(toss.placeOrder(any())).thenReturn(new TossOrder("ORD1", "005930", "BUY", "MARKET", "FILLED",
                null, "20", "1000000", "KRW", null, null, exec));

        boolean ok = liveExecutor.buy("005930", "KR", BigDecimal.valueOf(1_000_000), BigDecimal.valueOf(50_000));

        assertThat(ok).isTrue();
        verify(toss, times(1)).placeOrder(any());
        verify(positionMapper).insert(argThat(p -> !p.isDryRun()));
    }

    @Test
    void budget_over_cap_never_creates_order() {
        boolean ok = executor.buy("005930", "KR", BigDecimal.valueOf(1_000_001), BigDecimal.valueOf(50_000));

        assertThat(ok).isFalse();
        verify(toss, never()).placeOrder(any());
        verify(positionMapper, never()).insert(any());
    }

    @Test
    void sell_follows_position_dry_run_flag_not_global_state() {
        AutoTradeProperties liveProps = new AutoTradeProperties(false, BigDecimal.valueOf(5_000_000), 5,
                BigDecimal.valueOf(1_000_000), 15.0, 10.0, 10.0, "", "0 */5 9-15 * * MON-FRI", 20, 1.5, 20.0, 2.0, 200.0, BigDecimal.valueOf(500_000_000), 20, 4,
                new AutoTradeProperties.Gate(35, 1, 3));
        OrderExecutor liveExecutor = new OrderExecutor(liveProps, stateMapper, positionMapper, logMapper, toss, discord);
        when(stateMapper.find()).thenReturn(stateWith(false));

        AutoTradePosition dryRunPosition = new AutoTradePosition();
        dryRunPosition.setId(1L);
        dryRunPosition.setSymbol("005930");
        dryRunPosition.setMarket("KR");
        dryRunPosition.setEntryPrice(BigDecimal.valueOf(50_000));
        dryRunPosition.setEntryQty(BigDecimal.valueOf(20));
        dryRunPosition.setDryRun(true); // 드라이런으로 진입한 가상 포지션

        liveExecutor.sell(dryRunPosition, ExitReason.HARD_STOP, BigDecimal.valueOf(45_000));

        verify(toss, never()).placeOrder(any()); // 전역은 실주문 모드지만 이 포지션은 가상이라 실주문 안 나감
    }
}
