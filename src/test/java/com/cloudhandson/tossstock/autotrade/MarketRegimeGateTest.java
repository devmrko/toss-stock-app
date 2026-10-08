package com.cloudhandson.tossstock.autotrade;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * MarketRegimeGate.evaluate 검증. 설계: docs/design/808-auto-trade-engine/fn-market-regime-gate.md,
 * docs/design/879-per-market-regime-gate/README.md §6
 */
class MarketRegimeGateTest {

    private static final AutoTradeProperties.Gate GATE = new AutoTradeProperties.Gate(35, 100, 2.5, 20, 0.2);

    /** 표본이 충분한 경우(KR 은 3,699종목). */
    private static final int ENOUGH = 3699;

    @Test
    void healthy_market_allows_buy() {
        assertThat(MarketRegimeGate.evaluate(50, ENOUGH, GATE)).isTrue();
    }

    @Test
    void low_breadth_blocks_buy() {
        assertThat(MarketRegimeGate.evaluate(28, ENOUGH, GATE)).isFalse();
    }

    @Test
    void breadth_exactly_at_threshold_allows() {
        assertThat(MarketRegimeGate.evaluate(35, ENOUGH, GATE)).isTrue();
    }

    @Test
    void out_of_range_breadth_throws() {
        assertThatThrownBy(() -> MarketRegimeGate.evaluate(150, ENOUGH, GATE))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MarketRegimeGate.evaluate(-5, ENOUGH, GATE))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ---- #879 표본 부족은 '신호 없음'으로 보고 통과 ----

    @Test
    void 표본이_부족하면_breadth가_0이어도_통과한다() {
        // 인수조건 3 — 실측(2026-10-08): 기준일 join 에 US 가 7종목뿐이었다.
        // 7종목짜리 breadth 로 실매매를 막을 수 없고, KR 분포로 캘리브레이션한 임계값을
        // 표본이 다른 시장에 적용할 근거도 없다.
        assertThat(MarketRegimeGate.evaluate(0, 7, GATE)).isTrue();
        assertThat(MarketRegimeGate.evaluate(43, 7, GATE)).isTrue();
    }

    @Test
    void 표본이_0이어도_통과한다() {
        assertThat(MarketRegimeGate.evaluate(0, 0, GATE)).isTrue();
    }

    @Test
    void 표본이_최소치에_도달하면_게이트가_작동한다() {
        // 인수조건 4 — 커버리지가 늘면 코드 변경 없이 켜진다.
        assertThat(MarketRegimeGate.evaluate(28, 99, GATE)).isTrue();    // 아직 신호 없음
        assertThat(MarketRegimeGate.evaluate(28, 100, GATE)).isFalse();  // 작동 시작
    }
}
