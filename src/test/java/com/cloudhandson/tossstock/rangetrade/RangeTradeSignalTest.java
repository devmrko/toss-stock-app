package com.cloudhandson.tossstock.rangetrade;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static com.cloudhandson.tossstock.rangetrade.RangeTradeTestFixtures.props;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 매수/익절/손절 판정 검증 — 설계: docs/design/818-range-trade-swing/fn-range-trade-signal.md §10.
 * 밴드는 [100, 130](운영 최소폭 26% 충족), 운영 파라미터(entry 10% / exit 10% / breakdown 5%) 사용.
 */
class RangeTradeSignalTest {

    private static final BigDecimal LOW = BigDecimal.valueOf(100);
    private static final BigDecimal HIGH = BigDecimal.valueOf(130);
    private final RangeTradeProperties props = props();

    private RangeTradeSignal.Signal decide(double current, boolean holding) {
        return RangeTradeSignal.decide(BigDecimal.valueOf(current), LOW, HIGH, holding, props);
    }

    @Test
    void not_holding_near_band_bottom_buys() {
        assertThat(decide(100, false)).isEqualTo(RangeTradeSignal.Signal.BUY);
        assertThat(decide(105, false)).isEqualTo(RangeTradeSignal.Signal.BUY);  // 하단 +5%
        assertThat(decide(95, false)).isEqualTo(RangeTradeSignal.Signal.BUY);   // 하단 아래도 진입구간
    }

    @Test
    void not_holding_in_middle_of_band_does_nothing() {
        assertThat(decide(115, false)).isEqualTo(RangeTradeSignal.Signal.NONE);
        assertThat(decide(110.01, false)).isEqualTo(RangeTradeSignal.Signal.NONE); // 진입 상한 바로 위
    }

    @Test
    void holding_near_band_top_takes_profit() {
        // profitFloor = 130 * 0.9 = 117
        assertThat(decide(117, true)).isEqualTo(RangeTradeSignal.Signal.PROFIT_TAKE);
        assertThat(decide(130, true)).isEqualTo(RangeTradeSignal.Signal.PROFIT_TAKE);
        assertThat(decide(140, true)).isEqualTo(RangeTradeSignal.Signal.PROFIT_TAKE); // 상단 돌파도 익절
    }

    @Test
    void holding_below_entry_band_bottom_stops_out() {
        // breakdownFloor = 100 * 0.95 = 95
        assertThat(decide(94, true)).isEqualTo(RangeTradeSignal.Signal.RANGE_BREAKDOWN);
        assertThat(decide(50, true)).isEqualTo(RangeTradeSignal.Signal.RANGE_BREAKDOWN);
    }

    @Test
    void holding_in_middle_of_band_keeps_position() {
        assertThat(decide(96, true)).isEqualTo(RangeTradeSignal.Signal.NONE);
        assertThat(decide(116.99, true)).isEqualTo(RangeTradeSignal.Signal.NONE);
    }

    @Test
    void boundaries_are_inclusive() {
        assertThat(decide(110, false)).isEqualTo(RangeTradeSignal.Signal.BUY);             // == entryCeiling
        assertThat(decide(95, true)).isEqualTo(RangeTradeSignal.Signal.RANGE_BREAKDOWN);   // == breakdownFloor
        assertThat(decide(117, true)).isEqualTo(RangeTradeSignal.Signal.PROFIT_TAKE);      // == profitFloor
    }

    @Test
    void holding_true_never_buys_and_holding_false_never_sells() {
        assertThat(decide(100, true)).isEqualTo(RangeTradeSignal.Signal.NONE);   // 보유 중엔 BUY 없음
        assertThat(decide(130, false)).isEqualTo(RangeTradeSignal.Signal.NONE);  // 미보유엔 익절 없음
        assertThat(decide(50, false)).isEqualTo(RangeTradeSignal.Signal.BUY);    // 미보유엔 손절 없음(진입구간 판정만)
    }

    @Test
    void invalid_inputs_throw() {
        assertThatThrownBy(() -> RangeTradeSignal.decide(BigDecimal.ZERO, LOW, HIGH, false, props))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RangeTradeSignal.decide(BigDecimal.valueOf(-1), LOW, HIGH, false, props))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RangeTradeSignal.decide(null, LOW, HIGH, false, props))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RangeTradeSignal.decide(BigDecimal.valueOf(110), HIGH, LOW, false, props))
                .isInstanceOf(IllegalArgumentException.class); // 하단 > 상단
        assertThatThrownBy(() -> RangeTradeSignal.decide(BigDecimal.valueOf(110), LOW, LOW, true, props))
                .isInstanceOf(IllegalArgumentException.class); // 하단 == 상단
    }

    /**
     * 설정 검증(fn 설계서 §10 마지막 항목) — 진입 직후 바로 손절/익절이 터지는 모순이 없는지 수치로 확인.
     * 설계서 본문은 "entryCeiling < breakdownFloor 성립"이라 적었지만 그건 부등호가 뒤집힌 오기다
     * (entryCeiling = 하단×1.10, breakdownFloor = 하단×0.95 이므로 구조적으로 항상 entryCeiling > breakdownFloor).
     * 실제로 필요한 안전 조건은 "진입구간의 어떤 가격에 사도 즉시 손절/익절에 걸리지 않는다" 이고,
     * 그건 breakdownFloor < entryCeiling < profitFloor 로 표현된다(2026-10-01 Developer 단계에서 설계서 정정).
     */
    @Test
    void default_parameters_cannot_trigger_immediate_exit_at_entry() {
        BigDecimal entryCeiling = RangeTradeSignal.entryCeiling(LOW, props);   // 110
        BigDecimal breakdownFloor = RangeTradeSignal.breakdownFloor(LOW, props); // 95
        BigDecimal profitFloor = RangeTradeSignal.profitFloor(HIGH, props);     // 117

        assertThat(breakdownFloor).isLessThan(entryCeiling);
        assertThat(entryCeiling).isLessThan(profitFloor);

        // 진입구간의 최악(상한)·최선(하단) 가격 모두 매수 직후엔 NONE(계속 보유)이어야 한다.
        assertThat(RangeTradeSignal.decide(entryCeiling, LOW, HIGH, true, props))
                .isEqualTo(RangeTradeSignal.Signal.NONE);
        assertThat(RangeTradeSignal.decide(LOW, LOW, HIGH, true, props))
                .isEqualTo(RangeTradeSignal.Signal.NONE);
    }

    @Test
    void worst_case_round_trip_at_min_band_width_still_profitable() {
        // 설계서 §12 역산 전제 확인: 최소폭(26%) 밴드에서 "진입구간 꼭대기에 사서 익절구간 바닥에 파는"
        // 최악의 왕복도 왕복비용(0.23%) 차감 후 남아야 한다.
        // 참고(2026-10-01 실측): §12 역산은 폭을 하단 대비(w=(high-low)/low)로 계산했지만 구현은
        // fn 설계서 §5대로 중간값 대비로 계산한다 → 같은 26.0 임계값이 구현에선 더 엄격(하단 대비 29.9%).
        // 그래서 실제 최악 순수익은 역산값 3.25%보다 여유 있게 나온다(≈6.0%).
        BigDecimal low = BigDecimal.valueOf(100);
        BigDecimal high = BigDecimal.valueOf(129.885); // 폭 26.0%
        double buy = RangeTradeSignal.entryCeiling(low, props).doubleValue();
        double sell = RangeTradeSignal.profitFloor(high, props).doubleValue();
        double netPct = (sell - buy) / buy * 100 - 0.23;

        assertThat(netPct).isGreaterThan(3.0); // 목표 순수익 3%(가정값) 이상
    }
}
