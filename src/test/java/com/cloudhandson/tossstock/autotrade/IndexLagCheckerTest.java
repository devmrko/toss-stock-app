package com.cloudhandson.tossstock.autotrade;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #873 원칙 §4 "지수 하락기 보정 — 지수 대비 -10% 언더퍼폼 시 교체 고려".
 * 설계: docs/design/873-principle-sell-rules-completion/README.md §6
 */
class IndexLagCheckerTest {

    @Test
    void 지수_하락기에_10퍼센트포인트_이상_뒤처지면_true() {
        // 인수조건 1 — 지수 -5%, 종목 -16% → 차이 -11%p
        assertThat(IndexLagChecker.lagsIndex(-16.0, -5.0, 10.0)).isTrue();
    }

    @Test
    void 언더퍼폼이_부족하면_false() {
        // 지수 -5%, 종목 -10% → 차이 -5%p
        assertThat(IndexLagChecker.lagsIndex(-10.0, -5.0, 10.0)).isFalse();
    }

    @Test
    void 지수_상승기면_아무리_뒤처져도_false() {
        // 인수조건 2 — 원칙은 "지수 하락기에는 …"으로 조건부다.
        // 상승장에서 뒤처지는 것만으로는 교체 사유가 아니다. 지수 +5%, 종목 -6% → 차이 -11%p
        assertThat(IndexLagChecker.lagsIndex(-6.0, 5.0, 10.0)).isFalse();
        // 지수 0%(하락기 아님)도 제외
        assertThat(IndexLagChecker.lagsIndex(-20.0, 0.0, 10.0)).isFalse();
    }

    @Test
    void 경계값_정확히_10퍼센트포인트는_포함() {
        // 지수 -5%, 종목 -15% → 차이 정확히 -10%p
        assertThat(IndexLagChecker.lagsIndex(-15.0, -5.0, 10.0)).isTrue();
        // 바로 위(-9.99%p)는 제외
        assertThat(IndexLagChecker.lagsIndex(-14.99, -5.0, 10.0)).isFalse();
    }

    @Test
    void 데이터가_없으면_false() {
        // 인수조건 8 — 알림은 근거가 확실할 때만 보낸다.
        assertThat(IndexLagChecker.lagsIndex(null, -5.0, 10.0)).isFalse();
        assertThat(IndexLagChecker.lagsIndex(-16.0, null, 10.0)).isFalse();
        assertThat(IndexLagChecker.lagsIndex(null, null, 10.0)).isFalse();
    }

    @Test
    void 임계값_부호는_무시한다() {
        // 설정에 -10 을 넣어도 10 과 같게 동작해야 한다(설정 실수 방어).
        assertThat(IndexLagChecker.lagsIndex(-16.0, -5.0, -10.0)).isTrue();
    }
}
