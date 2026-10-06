package com.cloudhandson.tossstock.rangetrade;

import com.cloudhandson.tossstock.briefing.DiscordClient;
import com.cloudhandson.tossstock.market.DailyOhlcv;
import com.cloudhandson.tossstock.market.DailyOhlcvMapper;
import com.cloudhandson.tossstock.market.DailyRangeStats;
import com.cloudhandson.tossstock.toss.PriceCache;
import com.cloudhandson.tossstock.toss.dto.TossPrice;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static com.cloudhandson.tossstock.rangetrade.RangeTradeTestFixtures.props;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 틱 오케스트레이션의 내구성 검증 — "한 종목 예외가 전체 틱을 죽이지 않는다"(설계서 §9, #808 실사례 재발 방지).
 * 설계: docs/design/818-range-trade-swing/README.md §8, §9
 */
class RangeTradeSchedulerTest {

    private RangeTradeStateMapper stateMapper;
    private RangeTradePositionMapper positionMapper;
    private DailyOhlcvMapper dailyMapper;
    private PriceCache priceCache;
    private BadNewsGate badNewsGate;
    private EarningsCalendarGate earningsGate;
    private RangeOrderExecutor orderExecutor;
    private DiscordClient discord;
    private RangeTradeScheduler scheduler;

    @BeforeEach
    void setUp() {
        stateMapper = mock(RangeTradeStateMapper.class);
        positionMapper = mock(RangeTradePositionMapper.class);
        dailyMapper = mock(DailyOhlcvMapper.class);
        priceCache = mock(PriceCache.class);
        badNewsGate = mock(BadNewsGate.class);
        earningsGate = mock(EarningsCalendarGate.class);
        orderExecutor = mock(RangeOrderExecutor.class);
        discord = mock(DiscordClient.class);
        scheduler = new RangeTradeScheduler(props(true), stateMapper, positionMapper, dailyMapper,
                priceCache, badNewsGate, earningsGate, orderExecutor, discord);
        when(dailyMapper.rangeStatsBatch(any(LocalDate.class), anyInt())).thenReturn(List.of());
        when(positionMapper.findAll()).thenReturn(List.of()); // 서킷브레이커 평가손익 계산용 기본값
    }

    private RangeTradeState state(boolean tripped) {
        RangeTradeState s = new RangeTradeState();
        s.setDryRun(true);
        s.setCircuitBreakerTripped(tripped);
        s.setTotalBudget(BigDecimal.valueOf(1_000_000)); // props() 기본 예산과 동일
        return s;
    }

    private RangeTradePosition holding(long id, String symbol) {
        RangeTradePosition p = new RangeTradePosition();
        p.setId(id);
        p.setSymbol(symbol);
        p.setMarket("KR");
        p.setEntryPrice(BigDecimal.valueOf(105_000));
        p.setEntryQty(BigDecimal.valueOf(4));
        p.setRangeLowAtEntry(BigDecimal.valueOf(100_000));
        p.setRangeHighAtEntry(BigDecimal.valueOf(130_000));
        p.setDryRun(true);
        return p;
    }

    @Test
    void missing_state_row_does_nothing() {
        when(stateMapper.find()).thenReturn(null);

        scheduler.tick();

        verify(positionMapper, never()).findHolding();
        verify(dailyMapper, never()).rangeStatsBatch(any(LocalDate.class), anyInt());
    }

    @Test
    void exception_on_one_symbol_does_not_stop_other_symbols_or_the_scan() {
        when(stateMapper.find()).thenReturn(state(false));
        when(positionMapper.findHolding()).thenReturn(List.of(holding(1L, "AAAAAA"), holding(2L, "BBBBBB")));
        when(positionMapper.countHolding()).thenReturn(1);
        when(priceCache.get(List.of("AAAAAA"))).thenThrow(new RuntimeException("HTTP 429 too many requests"));
        when(priceCache.get(List.of("BBBBBB")))
                .thenReturn(List.of(new TossPrice("BBBBBB", "120000", "KRW", null))); // 익절 구간(≥117,000)
        when(badNewsGate.hasStrongBadNews("BBBBBB")).thenReturn(false);

        scheduler.tick();

        verify(orderExecutor).sell(any(), eq(RangeExitReason.PROFIT_TAKE), any(), any());
        verify(dailyMapper).rangeStatsBatch(any(LocalDate.class), anyInt()); // 스캔까지 도달
    }

    @Test
    void bad_news_sells_immediately_regardless_of_price() {
        when(stateMapper.find()).thenReturn(state(false));
        when(positionMapper.findHolding()).thenReturn(List.of(holding(1L, "AAAAAA")));
        when(positionMapper.countHolding()).thenReturn(1);
        when(priceCache.get(List.of("AAAAAA")))
                .thenReturn(List.of(new TossPrice("AAAAAA", "110000", "KRW", null))); // 밴드 중간
        when(badNewsGate.hasStrongBadNews("AAAAAA")).thenReturn(true);

        scheduler.tick();

        verify(orderExecutor).sell(any(), eq(RangeExitReason.BAD_NEWS), any(), any());
    }

    @Test
    void breakdown_below_entry_band_bottom_stops_out() {
        when(stateMapper.find()).thenReturn(state(false));
        when(positionMapper.findHolding()).thenReturn(List.of(holding(1L, "AAAAAA")));
        when(positionMapper.countHolding()).thenReturn(1);
        when(priceCache.get(List.of("AAAAAA")))
                .thenReturn(List.of(new TossPrice("AAAAAA", "94000", "KRW", null))); // 진입 하단 -6%

        scheduler.tick();

        verify(orderExecutor).sell(any(), eq(RangeExitReason.RANGE_BREAKDOWN), any(), any());
    }

    @Test
    void tripped_circuit_breaker_skips_new_buy_scan_but_still_checked_holdings() {
        when(stateMapper.find()).thenReturn(state(true));
        when(positionMapper.findHolding()).thenReturn(List.of(holding(1L, "AAAAAA")));
        when(priceCache.get(List.of("AAAAAA")))
                .thenReturn(List.of(new TossPrice("AAAAAA", "94000", "KRW", null)));

        scheduler.tick();

        verify(orderExecutor).sell(any(), eq(RangeExitReason.RANGE_BREAKDOWN), any(), any()); // 매도는 수행
        verify(dailyMapper, never()).rangeStatsBatch(any(LocalDate.class), anyInt()); // 신규 스캔은 안 함
    }

    @Test
    void fresh_equity_breach_trips_circuit_breaker_and_skips_scan() {
        // 2026-10-02 추가 — 설계서 v1엔 자동 트립 로직이 없었음(QA 지적). 총예산 100만원인데
        // 청산 포지션 손실만으로 -30%(기준 -15% 초과) → 이번 틱에서 트립되고 신규 스캔은 생략.
        when(stateMapper.find()).thenReturn(state(false));
        when(positionMapper.findHolding()).thenReturn(List.of());
        RangeTradePosition exited = new RangeTradePosition();
        exited.setStatus("EXITED");
        exited.setEntryPrice(BigDecimal.valueOf(100_000));
        exited.setEntryQty(BigDecimal.valueOf(10));
        exited.setExitPrice(BigDecimal.valueOf(70_000)); // (70,000-100,000)*10 = -300,000 = 예산의 -30%
        when(positionMapper.findAll()).thenReturn(List.of(exited));

        scheduler.tick();

        verify(stateMapper).tripCircuitBreaker(any());
        verify(dailyMapper, never()).rangeStatsBatch(any(LocalDate.class), anyInt());
    }

    @Test
    void healthy_equity_does_not_trip() {
        when(stateMapper.find()).thenReturn(state(false));
        when(positionMapper.findHolding()).thenReturn(List.of());
        when(positionMapper.countHolding()).thenReturn(0);
        RangeTradePosition exited = new RangeTradePosition();
        exited.setStatus("EXITED");
        exited.setEntryPrice(BigDecimal.valueOf(100_000));
        exited.setEntryQty(BigDecimal.valueOf(10));
        exited.setExitPrice(BigDecimal.valueOf(103_000)); // +30,000 = 예산의 +3%, 트립 기준 한참 못 미침
        when(positionMapper.findAll()).thenReturn(List.of(exited));

        scheduler.tick();

        verify(stateMapper, never()).tripCircuitBreaker(any());
        verify(dailyMapper).rangeStatsBatch(any(LocalDate.class), anyInt()); // 스캔까지 정상 진행
    }

    @Test
    void sell_passes_decision_rationale_with_binding_threshold() {
        // #832 — 매도 호출 시 "어느 임계선이 발동했고 진입 대비 얼마였나"가 근거 문자열로 전달돼야 한다.
        when(stateMapper.find()).thenReturn(state(false));
        when(positionMapper.findHolding()).thenReturn(List.of(holding(1L, "AAAAAA")));
        when(positionMapper.countHolding()).thenReturn(1);
        when(priceCache.get(List.of("AAAAAA")))
                .thenReturn(List.of(new TossPrice("AAAAAA", "94000", "KRW", null))); // 손절선(95,000) 이탈

        scheduler.tick();

        ArgumentCaptor<String> rationale = ArgumentCaptor.forClass(String.class);
        verify(orderExecutor).sell(any(), eq(RangeExitReason.RANGE_BREAKDOWN), any(), rationale.capture());
        assertThat(rationale.getValue()).isEqualTo("밴드이탈손절(손절선95000) 현재94000 진입105000 수익-10.48% "
                + "밴드100000~130000 익절선117000/손절선95000");
    }

    @Test
    void buy_passes_decision_rationale_with_band_and_entry_ceiling() {
        // #832 — 매수 호출 시 밴드/진입상한/밴드 내 위치가 근거 문자열로 전달돼야 한다.
        when(stateMapper.find()).thenReturn(state(false));
        when(positionMapper.findHolding()).thenReturn(List.of());
        when(positionMapper.countHolding()).thenReturn(0);
        when(dailyMapper.rangeStatsBatch(any(LocalDate.class), anyInt())).thenReturn(List.of(stats("AAAAAA")));
        when(dailyMapper.recentForSymbols(any(), any(LocalDate.class)))
                .thenReturn(oscillatingBars("AAAAAA", 61, 100_000, 130_000));

        scheduler.tick();

        ArgumentCaptor<String> rationale = ArgumentCaptor.forClass(String.class);
        verify(orderExecutor).buy(eq("AAAAAA"), any(), any(), any(), any(), rationale.capture());
        assertThat(rationale.getValue())
                .isEqualTo("밴드100000~130000 진입상한110000 매수가100000(밴드내0%)");
    }

    /** 밴드[low, high] 안에서 종가가 왕복하는 일봉 n개(추세 없음) — 종목코드까지 지정해 loadBars 키와 맞춘다. */
    private static List<DailyOhlcv> oscillatingBars(String symbol, int n, double low, double high) {
        List<DailyOhlcv> out = new ArrayList<>();
        LocalDate start = LocalDate.of(2026, 1, 5);
        for (int i = 0; i < n; i++) {
            BigDecimal close = BigDecimal.valueOf(i % 2 == 0 ? low : high);
            out.add(new DailyOhlcv(symbol, start.plusDays(i), close, BigDecimal.valueOf(high),
                    BigDecimal.valueOf(low), close, 1_000_000L));
        }
        return out;
    }

    /** 1차 스크리닝을 통과하는 박스권 통계(밴드 100,000~130,000 · 추세 없음). */
    private static DailyRangeStats stats(String symbol) {
        DailyRangeStats s = new DailyRangeStats();
        s.setSymbol(symbol);
        s.setBarCount(61);
        s.setMaxHigh(BigDecimal.valueOf(130_000));
        s.setMinLow(BigDecimal.valueOf(100_000));
        s.setLastClose(BigDecimal.valueOf(100_000));
        s.setFirstHalfAvgClose(BigDecimal.valueOf(115_000));
        s.setSecondHalfAvgClose(BigDecimal.valueOf(115_000));
        return s;
    }

    @Test
    void no_open_slot_skips_scan() {
        when(stateMapper.find()).thenReturn(state(false));
        when(positionMapper.findHolding()).thenReturn(List.of());
        when(positionMapper.countHolding()).thenReturn(2); // max-symbols=2

        scheduler.tick();

        verify(dailyMapper, never()).rangeStatsBatch(any(LocalDate.class), anyInt());
    }
}
