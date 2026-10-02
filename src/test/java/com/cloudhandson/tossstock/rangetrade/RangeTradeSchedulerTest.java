package com.cloudhandson.tossstock.rangetrade;

import com.cloudhandson.tossstock.briefing.DiscordClient;
import com.cloudhandson.tossstock.market.DailyOhlcvMapper;
import com.cloudhandson.tossstock.toss.PriceCache;
import com.cloudhandson.tossstock.toss.dto.TossPrice;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static com.cloudhandson.tossstock.rangetrade.RangeTradeTestFixtures.props;
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

        verify(orderExecutor).sell(any(), eq(RangeExitReason.PROFIT_TAKE), any());
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

        verify(orderExecutor).sell(any(), eq(RangeExitReason.BAD_NEWS), any());
    }

    @Test
    void breakdown_below_entry_band_bottom_stops_out() {
        when(stateMapper.find()).thenReturn(state(false));
        when(positionMapper.findHolding()).thenReturn(List.of(holding(1L, "AAAAAA")));
        when(positionMapper.countHolding()).thenReturn(1);
        when(priceCache.get(List.of("AAAAAA")))
                .thenReturn(List.of(new TossPrice("AAAAAA", "94000", "KRW", null))); // 진입 하단 -6%

        scheduler.tick();

        verify(orderExecutor).sell(any(), eq(RangeExitReason.RANGE_BREAKDOWN), any());
    }

    @Test
    void tripped_circuit_breaker_skips_new_buy_scan_but_still_checked_holdings() {
        when(stateMapper.find()).thenReturn(state(true));
        when(positionMapper.findHolding()).thenReturn(List.of(holding(1L, "AAAAAA")));
        when(priceCache.get(List.of("AAAAAA")))
                .thenReturn(List.of(new TossPrice("AAAAAA", "94000", "KRW", null)));

        scheduler.tick();

        verify(orderExecutor).sell(any(), eq(RangeExitReason.RANGE_BREAKDOWN), any()); // 매도는 수행
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
    void no_open_slot_skips_scan() {
        when(stateMapper.find()).thenReturn(state(false));
        when(positionMapper.findHolding()).thenReturn(List.of());
        when(positionMapper.countHolding()).thenReturn(2); // max-symbols=2

        scheduler.tick();

        verify(dailyMapper, never()).rangeStatsBatch(any(LocalDate.class), anyInt());
    }
}
