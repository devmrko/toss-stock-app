package com.cloudhandson.tossstock.rangetrade;

import com.cloudhandson.tossstock.briefing.DiscordClient;
import com.cloudhandson.tossstock.toss.TossApiClient;
import com.cloudhandson.tossstock.toss.dto.TossOrder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static com.cloudhandson.tossstock.rangetrade.RangeTradeTestFixtures.props;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.argThat;

/**
 * RangeOrderExecutor 안전장치 검증 — 가장 중요한 테스트: dryRun 일 때 실주문이 절대 안 나가는지.
 * 모델: docs/design/808-auto-trade-engine/fn-order-executor.md · 설계: docs/design/818-range-trade-swing/README.md §10
 */
class RangeOrderExecutorTest {

    private static final BigDecimal BAND_LOW = BigDecimal.valueOf(100_000);
    private static final BigDecimal BAND_HIGH = BigDecimal.valueOf(130_000);
    private static final BigDecimal PRICE = BigDecimal.valueOf(105_000);
    private static final BigDecimal BUDGET = BigDecimal.valueOf(500_000);
    /** #832 결정근거 스냅샷 — message 앞부분에 그대로 남아야 한다. */
    private static final String RATIONALE = "밴드100000~130000 진입상한110000 매수가105000(밴드내16.67%)";
    private static final String SELL_RATIONALE =
            "밴드이탈손절(손절선95000) 현재94000 진입105000 수익-10.48% 밴드100000~130000 익절선117000/손절선95000";

    private RangeTradeStateMapper stateMapper;
    private RangeTradePositionMapper positionMapper;
    private RangeTradeOrderLogMapper logMapper;
    private TossApiClient toss;
    private DiscordClient discord;
    private RangeOrderExecutor dryRunExecutor;

    @BeforeEach
    void setUp() {
        stateMapper = mock(RangeTradeStateMapper.class);
        positionMapper = mock(RangeTradePositionMapper.class);
        logMapper = mock(RangeTradeOrderLogMapper.class);
        toss = mock(TossApiClient.class);
        discord = mock(DiscordClient.class);
        dryRunExecutor = executor(props(true));
    }

    private RangeOrderExecutor executor(RangeTradeProperties p) {
        return new RangeOrderExecutor(p, stateMapper, positionMapper, logMapper, toss, discord);
    }

    private RangeTradeState stateWith(boolean dbDryRun) {
        RangeTradeState s = new RangeTradeState();
        s.setDryRun(dbDryRun);
        s.setTotalBudget(BigDecimal.valueOf(1_000_000));
        s.setMaxSymbols(2);
        s.setPerSymbolBudget(BUDGET);
        return s;
    }

    private static TossOrder filledOrder() {
        return order("FILLED", "105000");
    }

    private static TossOrder order(String status, String avgFilledPrice) {
        TossOrder.Execution exec = new TossOrder.Execution("5", avgFilledPrice, "525000", "0", "0", null, null);
        return new TossOrder("ORD1", "005930", "BUY", "MARKET", status, null, "5", "525000", "KRW",
                null, null, exec);
    }

    @Test
    void config_dry_run_true_never_calls_place_order() {
        when(stateMapper.find()).thenReturn(stateWith(false)); // DB는 false 여도

        boolean ok = dryRunExecutor.buy("005930", BUDGET, PRICE, BAND_LOW, BAND_HIGH, RATIONALE);

        assertThat(ok).isTrue();
        verify(toss, never()).placeOrder(any());
        verify(positionMapper).insert(argThat(RangeTradePosition::isDryRun));
    }

    @Test
    void db_dry_run_true_also_blocks_real_order_even_if_config_false() {
        when(stateMapper.find()).thenReturn(stateWith(true)); // DB가 true면 이중 안전장치로 드라이런

        executor(props(false)).buy("005930", BUDGET, PRICE, BAND_LOW, BAND_HIGH, RATIONALE);

        verify(toss, never()).placeOrder(any());
    }

    @Test
    void missing_state_row_falls_back_to_dry_run() {
        when(stateMapper.find()).thenReturn(null);

        executor(props(false)).buy("005930", BUDGET, PRICE, BAND_LOW, BAND_HIGH, RATIONALE);

        verify(toss, never()).placeOrder(any());
    }

    @Test
    void both_false_places_real_order_exactly_once_and_records_entry_band() {
        when(stateMapper.find()).thenReturn(stateWith(false));
        when(toss.placeOrder(any())).thenReturn(filledOrder());

        boolean ok = executor(props(false)).buy("005930", BUDGET, PRICE, BAND_LOW, BAND_HIGH, RATIONALE);

        assertThat(ok).isTrue();
        verify(toss, times(1)).placeOrder(any());
        verify(positionMapper).insert(argThat(p -> !p.isDryRun()
                && p.getRangeLowAtEntry().compareTo(BAND_LOW) == 0      // 진입 시점 밴드 고정 저장
                && p.getRangeHighAtEntry().compareTo(BAND_HIGH) == 0
                && p.getEntryQty().compareTo(BigDecimal.valueOf(4)) == 0 // 500,000 / 105,000 = 4주(버림)
                && "KR".equals(p.getMarket())));
    }

    @Test
    void budget_over_cap_never_creates_order() {
        boolean ok = dryRunExecutor.buy("005930", BUDGET.add(BigDecimal.ONE), PRICE, BAND_LOW, BAND_HIGH, RATIONALE);

        assertThat(ok).isFalse();
        verify(toss, never()).placeOrder(any());
        verify(positionMapper, never()).insert(any());
    }

    @Test
    void zero_quantity_budget_never_creates_order() {
        when(stateMapper.find()).thenReturn(stateWith(true));

        boolean ok = dryRunExecutor.buy("005930", BigDecimal.valueOf(50_000), PRICE, BAND_LOW, BAND_HIGH, RATIONALE);

        assertThat(ok).isFalse();
        verify(positionMapper, never()).insert(any());
    }

    @Test
    void audit_log_failure_does_not_block_position_recording() {
        // 2026-10-01 #808 실사례: toss_order_id 길이초과(ORA-12899)로 감사로그 INSERT가 예외를 던져
        // 실제 체결된 매수가 포지션에 기록되지 않았고, "이미 보유 중" 체크가 안 돼 반복 매수로 이어짐.
        // 레인지 트랙은 처음부터 감사로그 실패와 포지션 기록을 분리한다.
        when(stateMapper.find()).thenReturn(stateWith(false));
        when(toss.placeOrder(any())).thenReturn(filledOrder());
        doThrow(new RuntimeException("ORA-12899: value too large")).when(logMapper).insert(any());

        boolean ok = executor(props(false)).buy("005930", BUDGET, PRICE, BAND_LOW, BAND_HIGH, RATIONALE);

        assertThat(ok).isTrue();
        verify(positionMapper).insert(argThat(p -> !p.isDryRun()));
    }

    @Test
    void audit_log_failure_does_not_block_exit_recording() {
        when(stateMapper.find()).thenReturn(stateWith(false));
        when(toss.placeOrder(any())).thenReturn(filledOrder());
        doThrow(new RuntimeException("ORA-12899: value too large")).when(logMapper).insert(any());

        boolean ok = executor(props(false)).sell(livePosition(), RangeExitReason.RANGE_BREAKDOWN,
                BigDecimal.valueOf(95_000), SELL_RATIONALE);

        assertThat(ok).isTrue();
        verify(positionMapper).markExited(any(), any(), any(), any());
    }

    @Test
    void order_failure_does_not_record_position() {
        when(stateMapper.find()).thenReturn(stateWith(false));
        when(toss.placeOrder(any())).thenThrow(new RuntimeException("HTTP 429 too many requests"));

        boolean ok = executor(props(false)).buy("005930", BUDGET, PRICE, BAND_LOW, BAND_HIGH, RATIONALE);

        assertThat(ok).isFalse();
        verify(positionMapper, never()).insert(any());
        verify(toss, times(1)).placeOrder(any()); // 재시도 안 함
    }

    @Test
    void sell_follows_position_dry_run_flag_not_global_state() {
        when(stateMapper.find()).thenReturn(stateWith(false));
        RangeTradePosition dryRunPosition = livePosition();
        dryRunPosition.setDryRun(true); // 드라이런으로 진입한 가상 포지션

        executor(props(false)).sell(dryRunPosition, RangeExitReason.PROFIT_TAKE, BigDecimal.valueOf(120_000), SELL_RATIONALE);

        verify(toss, never()).placeOrder(any()); // 전역은 실주문 모드지만 이 포지션은 가상
        verify(positionMapper).markExited(any(), any(), any(), any());
    }

    @Test
    void sell_records_exit_reason() {
        when(stateMapper.find()).thenReturn(stateWith(false));
        when(toss.placeOrder(any())).thenReturn(filledOrder());

        executor(props(false)).sell(livePosition(), RangeExitReason.BAD_NEWS, BigDecimal.valueOf(99_000), SELL_RATIONALE);

        verify(positionMapper).markExited(any(), any(), argThat("BAD_NEWS"::equals), any());
    }

    @Test
    void buy_log_message_carries_rationale_before_outcome() {
        when(stateMapper.find()).thenReturn(stateWith(true));

        dryRunExecutor.buy("005930", BUDGET, PRICE, BAND_LOW, BAND_HIGH, RATIONALE);

        verify(logMapper).insert(argThat(e ->
                (RATIONALE + " | 드라이런 — 실주문 안 함").equals(e.getMessage())));
    }

    @Test
    void sell_log_message_carries_rationale_even_when_order_fails() {
        when(stateMapper.find()).thenReturn(stateWith(false));
        when(toss.placeOrder(any())).thenThrow(new RuntimeException("HTTP 429 too many requests"));

        boolean ok = executor(props(false)).sell(livePosition(), RangeExitReason.RANGE_BREAKDOWN,
                BigDecimal.valueOf(94_000), SELL_RATIONALE);

        assertThat(ok).isFalse();
        // "왜 팔려고 했는지"는 주문 실패와 무관하게 남는다(설계 §6).
        verify(logMapper).insert(argThat(e -> e.getMessage().startsWith(SELL_RATIONALE + " | 주문 실패: ")));
    }

    @Test
    void budget_over_cap_keeps_existing_message_without_rationale() {
        dryRunExecutor.buy("005930", BUDGET.add(BigDecimal.ONE), PRICE, BAND_LOW, BAND_HIGH, RATIONALE);

        // 매수 자체가 안 된 경로는 기존 메시지 유지(설계 §3).
        verify(logMapper).insert(argThat(e -> "예산 상한 초과".equals(e.getMessage())));
    }

    @Test
    void over_long_rationale_is_truncated_to_column_limit() {
        when(stateMapper.find()).thenReturn(stateWith(true));

        dryRunExecutor.buy("005930", BUDGET, PRICE, BAND_LOW, BAND_HIGH, "가".repeat(600));

        verify(logMapper).insert(argThat(e -> e.getMessage().length() == 500));
    }

    @Test
    void 즉시_FILLED면_재조회_안_함() {
        when(stateMapper.find()).thenReturn(stateWith(false));
        when(toss.placeOrder(any())).thenReturn(order("FILLED", "105000"));

        executor(props(false)).buy("005930", BUDGET, PRICE, BAND_LOW, BAND_HIGH, RATIONALE);

        verify(toss, never()).getOrder(any());
        verify(logMapper).insert(argThat(e -> e.getRequestedPrice().compareTo(BigDecimal.valueOf(105000)) == 0));
    }

    @Test
    void PENDING에서_FILLED로_바뀌면_재조회된_체결가를_쓴다() {
        when(stateMapper.find()).thenReturn(stateWith(false));
        when(toss.placeOrder(any())).thenReturn(order("PENDING", "105000"));
        when(toss.getOrder("ORD1")).thenReturn(order("FILLED", "106200"));

        executor(props(false)).buy("005930", BUDGET, PRICE, BAND_LOW, BAND_HIGH, RATIONALE);

        verify(toss, times(1)).getOrder("ORD1");
        verify(logMapper).insert(argThat(e -> e.getRequestedPrice().compareTo(BigDecimal.valueOf(106200)) == 0));
    }

    @Test
    void 재시도_소진후에도_미확정이면_마지막_값으로_진행() {
        when(stateMapper.find()).thenReturn(stateWith(false));
        when(toss.placeOrder(any())).thenReturn(order("PENDING", "105000"));
        when(toss.getOrder("ORD1")).thenReturn(order("PENDING", "105500"));

        boolean ok = executor(props(false)).buy("005930", BUDGET, PRICE, BAND_LOW, BAND_HIGH, RATIONALE);

        assertThat(ok).isTrue();
        verify(toss, times(4)).getOrder("ORD1");
        verify(logMapper).insert(argThat(e -> e.getRequestedPrice().compareTo(BigDecimal.valueOf(105500)) == 0));
    }

    @Test
    void 재조회_예외는_거래_흐름을_막지_않는다() {
        when(stateMapper.find()).thenReturn(stateWith(false));
        when(toss.placeOrder(any())).thenReturn(order("PENDING", "105000"));
        when(toss.getOrder("ORD1")).thenThrow(new RuntimeException("HTTP 429"));

        boolean ok = executor(props(false)).buy("005930", BUDGET, PRICE, BAND_LOW, BAND_HIGH, RATIONALE);

        assertThat(ok).isTrue();
        verify(toss, times(1)).getOrder("ORD1");
        verify(logMapper).insert(argThat(e -> e.getRequestedPrice().compareTo(BigDecimal.valueOf(105000)) == 0));
    }

    private RangeTradePosition livePosition() {
        RangeTradePosition p = new RangeTradePosition();
        p.setId(1L);
        p.setSymbol("005930");
        p.setMarket("KR");
        p.setEntryPrice(BigDecimal.valueOf(105_000));
        p.setEntryQty(BigDecimal.valueOf(4));
        p.setRangeLowAtEntry(BAND_LOW);
        p.setRangeHighAtEntry(BAND_HIGH);
        p.setDryRun(false);
        return p;
    }
}
