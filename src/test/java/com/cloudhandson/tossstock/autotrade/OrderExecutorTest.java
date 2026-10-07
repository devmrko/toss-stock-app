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

    /** #832 결정근거 스냅샷 — message 앞부분에 그대로 남아야 한다. */
    private static final String RATIONALE =
            "PER11.6/PBR0.47(저평가) 펀더3/5(실적X재무O배당O유동O강도X) 인기:가격+2.3% 뉴스:S5 \"호재\"";
    private static final String SELL_RATIONALE =
            "하드스탑(피크50000→현재45000,-10%) 진입50000 수익-10% 스탑45000(하드45000/트레일45000)";

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
                BigDecimal.valueOf(1_000_000), 15.0, 10.0, 10.0, "", "0 */5 9-15 * * MON-FRI", 20, 1.5, 2.0, 20.0, 2.0, 200.0, BigDecimal.valueOf(500_000_000), BigDecimal.valueOf(350_000), 20, 4, 30, 60,
                new AutoTradeProperties.Gate(35));
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

        boolean ok = executor.buy("005930", "KR", BigDecimal.valueOf(1_000_000), BigDecimal.valueOf(50_000), RATIONALE);

        assertThat(ok).isTrue();
        verify(toss, never()).placeOrder(any());
        verify(positionMapper).insert(argThat(p -> p.isDryRun()));
    }

    @Test
    void db_dry_run_true_also_blocks_real_order_even_if_config_false() {
        AutoTradeProperties liveProps = new AutoTradeProperties(false, BigDecimal.valueOf(5_000_000), 5,
                BigDecimal.valueOf(1_000_000), 15.0, 10.0, 10.0, "", "0 */5 9-15 * * MON-FRI", 20, 1.5, 2.0, 20.0, 2.0, 200.0, BigDecimal.valueOf(500_000_000), BigDecimal.valueOf(350_000), 20, 4, 30, 60,
                new AutoTradeProperties.Gate(35));
        OrderExecutor liveExecutor = new OrderExecutor(liveProps, stateMapper, positionMapper, logMapper, toss, discord);
        when(stateMapper.find()).thenReturn(stateWith(true)); // DB가 true면 이중 안전장치로 드라이런

        liveExecutor.buy("005930", "KR", BigDecimal.valueOf(1_000_000), BigDecimal.valueOf(50_000), RATIONALE);

        verify(toss, never()).placeOrder(any());
    }

    @Test
    void both_false_places_real_order_exactly_once() {
        AutoTradeProperties liveProps = new AutoTradeProperties(false, BigDecimal.valueOf(5_000_000), 5,
                BigDecimal.valueOf(1_000_000), 15.0, 10.0, 10.0, "", "0 */5 9-15 * * MON-FRI", 20, 1.5, 2.0, 20.0, 2.0, 200.0, BigDecimal.valueOf(500_000_000), BigDecimal.valueOf(350_000), 20, 4, 30, 60,
                new AutoTradeProperties.Gate(35));
        OrderExecutor liveExecutor = new OrderExecutor(liveProps, stateMapper, positionMapper, logMapper, toss, discord);
        when(stateMapper.find()).thenReturn(stateWith(false));
        TossOrder.Execution exec = new TossOrder.Execution("20", "50000", "1000000", "0", "0", null, null);
        when(toss.placeOrder(any())).thenReturn(new TossOrder("ORD1", "005930", "BUY", "MARKET", "FILLED",
                null, "20", "1000000", "KRW", null, null, exec));

        boolean ok = liveExecutor.buy("005930", "KR", BigDecimal.valueOf(1_000_000), BigDecimal.valueOf(50_000), RATIONALE);

        assertThat(ok).isTrue();
        verify(toss, times(1)).placeOrder(any());
        verify(positionMapper).insert(argThat(p -> !p.isDryRun()));
    }

    @Test
    void budget_over_cap_never_creates_order() {
        boolean ok = executor.buy("005930", "KR", BigDecimal.valueOf(1_000_001), BigDecimal.valueOf(50_000), RATIONALE);

        assertThat(ok).isFalse();
        verify(toss, never()).placeOrder(any());
        verify(positionMapper, never()).insert(any());
    }

    @Test
    void audit_log_failure_does_not_block_position_recording() {
        // 2026-10-01 실사례: toss_order_id 컬럼 길이 초과(ORA-12899)로 saveLog가 예외를 던져
        // 실제 체결된 매수가 포지션에 기록되지 않았고, "이미 보유 중" 체크가 안 돼 같은 종목을
        // 틱마다 반복 매수(012330, 5회)로 이어짐. 감사로그 실패와 무관하게 포지션은 기록돼야 함.
        AutoTradeProperties liveProps = new AutoTradeProperties(false, BigDecimal.valueOf(5_000_000), 5,
                BigDecimal.valueOf(1_000_000), 15.0, 10.0, 10.0, "", "0 */5 9-15 * * MON-FRI", 20, 1.5, 2.0, 20.0, 2.0, 200.0, BigDecimal.valueOf(500_000_000), BigDecimal.valueOf(350_000), 20, 4, 30, 60,
                new AutoTradeProperties.Gate(35));
        OrderExecutor liveExecutor = new OrderExecutor(liveProps, stateMapper, positionMapper, logMapper, toss, discord);
        when(stateMapper.find()).thenReturn(stateWith(false));
        TossOrder.Execution exec = new TossOrder.Execution("20", "50000", "1000000", "0", "0", null, null);
        when(toss.placeOrder(any())).thenReturn(new TossOrder("ORD1", "005930", "BUY", "MARKET", "FILLED",
                null, "20", "1000000", "KRW", null, null, exec));
        doThrow(new RuntimeException("ORA-12899: value too large")).when(logMapper).insert(any());

        boolean ok = liveExecutor.buy("005930", "KR", BigDecimal.valueOf(1_000_000), BigDecimal.valueOf(50_000), RATIONALE);

        assertThat(ok).isTrue();
        verify(positionMapper).insert(argThat(p -> !p.isDryRun()));
    }

    @Test
    void sell_follows_position_dry_run_flag_not_global_state() {
        AutoTradeProperties liveProps = new AutoTradeProperties(false, BigDecimal.valueOf(5_000_000), 5,
                BigDecimal.valueOf(1_000_000), 15.0, 10.0, 10.0, "", "0 */5 9-15 * * MON-FRI", 20, 1.5, 2.0, 20.0, 2.0, 200.0, BigDecimal.valueOf(500_000_000), BigDecimal.valueOf(350_000), 20, 4, 30, 60,
                new AutoTradeProperties.Gate(35));
        OrderExecutor liveExecutor = new OrderExecutor(liveProps, stateMapper, positionMapper, logMapper, toss, discord);
        when(stateMapper.find()).thenReturn(stateWith(false));

        AutoTradePosition dryRunPosition = new AutoTradePosition();
        dryRunPosition.setId(1L);
        dryRunPosition.setSymbol("005930");
        dryRunPosition.setMarket("KR");
        dryRunPosition.setEntryPrice(BigDecimal.valueOf(50_000));
        dryRunPosition.setEntryQty(BigDecimal.valueOf(20));
        dryRunPosition.setDryRun(true); // 드라이런으로 진입한 가상 포지션

        liveExecutor.sell(dryRunPosition, ExitReason.HARD_STOP, BigDecimal.valueOf(45_000), SELL_RATIONALE);

        verify(toss, never()).placeOrder(any()); // 전역은 실주문 모드지만 이 포지션은 가상이라 실주문 안 나감
    }

    @Test
    void buy_log_message_carries_rationale_before_outcome() {
        when(stateMapper.find()).thenReturn(stateWith(true));

        executor.buy("005930", "KR", BigDecimal.valueOf(1_000_000), BigDecimal.valueOf(50_000), RATIONALE);

        verify(logMapper).insert(argThat(e ->
                (RATIONALE + " | 드라이런 — 실주문 안 함").equals(e.getMessage())));
    }

    @Test
    void sell_log_message_carries_rationale_even_when_order_fails() {
        AutoTradeProperties liveProps = new AutoTradeProperties(false, BigDecimal.valueOf(5_000_000), 5,
                BigDecimal.valueOf(1_000_000), 15.0, 10.0, 10.0, "", "0 */5 9-15 * * MON-FRI", 20, 1.5, 2.0, 20.0, 2.0, 200.0, BigDecimal.valueOf(500_000_000), BigDecimal.valueOf(350_000), 20, 4, 30, 60,
                new AutoTradeProperties.Gate(35));
        OrderExecutor liveExecutor = new OrderExecutor(liveProps, stateMapper, positionMapper, logMapper, toss, discord);
        when(stateMapper.find()).thenReturn(stateWith(false));
        when(toss.placeOrder(any())).thenThrow(new RuntimeException("HTTP 429 too many requests"));

        AutoTradePosition p = new AutoTradePosition();
        p.setId(1L);
        p.setSymbol("005930");
        p.setMarket("KR");
        p.setEntryPrice(BigDecimal.valueOf(50_000));
        p.setEntryQty(BigDecimal.valueOf(20));
        p.setDryRun(false);

        boolean ok = liveExecutor.sell(p, ExitReason.TRAIL_STOP, BigDecimal.valueOf(45_000), SELL_RATIONALE);

        assertThat(ok).isFalse();
        // "왜 팔려고 했는지"는 주문 실패와 무관하게 남는다(설계 §6).
        verify(logMapper).insert(argThat(e -> e.getMessage().startsWith(SELL_RATIONALE + " | 주문 실패: ")));
    }

    @Test
    void budget_over_cap_keeps_existing_message_without_rationale() {
        executor.buy("005930", "KR", BigDecimal.valueOf(1_000_001), BigDecimal.valueOf(50_000), RATIONALE);

        // 매수 자체가 안 된 경로는 기존 메시지 유지(설계 §3) — 호출부가 근거를 넘겼는지와 무관.
        verify(logMapper).insert(argThat(e -> "예산 상한 초과".equals(e.getMessage())));
    }

    @Test
    void over_long_rationale_is_truncated_to_column_limit() {
        when(stateMapper.find()).thenReturn(stateWith(true));

        executor.buy("005930", "KR", BigDecimal.valueOf(1_000_000), BigDecimal.valueOf(50_000), "가".repeat(600));

        verify(logMapper).insert(argThat(e -> e.getMessage().length() == 500));
    }
}
