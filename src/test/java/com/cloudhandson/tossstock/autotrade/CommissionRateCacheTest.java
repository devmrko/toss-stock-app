package com.cloudhandson.tossstock.autotrade;

import com.cloudhandson.tossstock.toss.TossApiClient;
import com.cloudhandson.tossstock.toss.dto.TossCommission;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * #863 수수료 요율 동적 조회. 설계: docs/design/863-dynamic-commission-rate/README.md §5
 */
class CommissionRateCacheTest {

    private TossApiClient toss;
    private CommissionRateCache cache;

    @BeforeEach
    void setUp() {
        toss = mock(TossApiClient.class);
        cache = new CommissionRateCache(toss);
    }

    /** 2026-10-08 실측 응답 그대로. */
    private static List<TossCommission> live() {
        return List.of(
                new TossCommission("KR", "0.00015", "2021-01-01", "9999-12-31"),
                new TossCommission("US", "0.001", null, "2026-10-09"));
    }

    // ---- pick (순수) ----

    @Test
    void pick_정상_요율을_파싱한다() {
        assertThat(CommissionRateCache.pick(live().get(0))).isEqualByComparingTo("0.00015");
        assertThat(CommissionRateCache.pick(live().get(1))).isEqualByComparingTo("0.001");
    }

    @Test
    void pick_비정상_값은_무시한다() {
        // 0·음수·과도한 값이 그대로 들어오면 수수료가 망가진다.
        assertThat(CommissionRateCache.pick(new TossCommission("KR", "0", null, null))).isNull();
        assertThat(CommissionRateCache.pick(new TossCommission("KR", "-0.001", null, null))).isNull();
        assertThat(CommissionRateCache.pick(new TossCommission("KR", "0.01", null, null))).isNull();  // 1%는 상한
        assertThat(CommissionRateCache.pick(new TossCommission("KR", "0.5", null, null))).isNull();
    }

    @Test
    void pick_파싱불가_입력은_무시한다() {
        assertThat(CommissionRateCache.pick(null)).isNull();
        assertThat(CommissionRateCache.pick(new TossCommission("KR", null, null, null))).isNull();
        assertThat(CommissionRateCache.pick(new TossCommission("KR", "", null, null))).isNull();
        assertThat(CommissionRateCache.pick(new TossCommission("KR", "무료", null, null))).isNull();
        assertThat(CommissionRateCache.pick(new TossCommission(null, "0.001", null, null))).isNull();
    }

    // ---- rateFor ----

    @Test
    void 조회값을_사용한다() {
        // 인수조건 1
        when(toss.getCommissions()).thenReturn(List.of(
                new TossCommission("KR", "0.0002", null, "9999-12-31")));

        assertThat(cache.rateFor("KR")).isEqualByComparingTo("0.0002");
    }

    @Test
    void 대소문자_무관하게_찾는다() {
        when(toss.getCommissions()).thenReturn(live());
        assertThat(cache.rateFor("us")).isEqualByComparingTo("0.001");
        assertThat(cache.rateFor("Kr")).isEqualByComparingTo("0.00015");
    }

    @Test
    void 조회_실패시_기본값을_쓰고_예외를_던지지_않는다() {
        // 인수조건 2 — 수수료를 못 구해 주문이 막히면 손절이 멈춘다.
        when(toss.getCommissions()).thenThrow(new RuntimeException("HTTP 401"));

        assertThat(cache.rateFor("KR"))
                .isEqualByComparingTo(TradingFeeCalculator.defaultCommissionRate("KR"));
        assertThat(cache.rateFor("US"))
                .isEqualByComparingTo(TradingFeeCalculator.defaultCommissionRate("US"));
    }

    @Test
    void 해당_시장이_없으면_기본값을_쓴다() {
        // 인수조건 3
        when(toss.getCommissions()).thenReturn(List.of(
                new TossCommission("KR", "0.00015", null, "9999-12-31")));

        assertThat(cache.rateFor("US"))
                .isEqualByComparingTo(TradingFeeCalculator.defaultCommissionRate("US"));
    }

    @Test
    void endDate가_지났어도_조회값을_쓴다() {
        // 인수조건 4 — 롤링 값으로 보이므로 만료가 "틀렸다"는 뜻이 아니다. 경고만 남긴다.
        when(toss.getCommissions()).thenReturn(List.of(
                new TossCommission("US", "0.0012", null, LocalDate.now().minusDays(5).toString())));

        assertThat(cache.rateFor("US")).isEqualByComparingTo("0.0012");
    }

    @Test
    void endDate_형식이_이상해도_요율은_쓴다() {
        when(toss.getCommissions()).thenReturn(List.of(
                new TossCommission("US", "0.0012", null, "상시")));

        assertThat(cache.rateFor("US")).isEqualByComparingTo("0.0012");
    }

    @Test
    void 같은날_반복_호출시_API는_한번만_부른다() {
        // 인수조건 5 — #847 에서 매 주문 조회는 레이트리밋 때문에 기각했다.
        when(toss.getCommissions()).thenReturn(live());

        cache.rateFor("KR");
        cache.rateFor("KR");
        cache.rateFor("US");

        verify(toss, times(1)).getCommissions();
    }

    @Test
    void 조회_실패도_당일_재시도하지_않는다() {
        // 실패마다 재시도하면 매 주문이 느려지고 레이트리밋을 때린다.
        when(toss.getCommissions()).thenThrow(new RuntimeException("HTTP 500"));

        cache.rateFor("KR");
        cache.rateFor("KR");

        verify(toss, times(1)).getCommissions();
    }

    @Test
    void 조회값이_기본값과_같으면_계산결과도_같다() {
        // 인수조건 6 — KR 은 조회값이 상수와 동일하므로 기존 계산이 바뀌지 않아야 한다.
        when(toss.getCommissions()).thenReturn(live());
        BigDecimal amount = new BigDecimal("1000000");

        assertThat(TradingFeeCalculator.commission(amount, "KR", cache.rateFor("KR")))
                .isEqualByComparingTo(TradingFeeCalculator.commission(amount, "KR"));
    }
}
