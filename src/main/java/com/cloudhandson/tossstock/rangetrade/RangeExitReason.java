package com.cloudhandson.tossstock.rangetrade;

/**
 * 레인지 트랙 매도 사유 — range_trade_position.exit_reason 값.
 * 설계: docs/design/818-range-trade-swing/README.md §6
 */
public enum RangeExitReason {
    /** 밴드 상단 근처 도달(익절). */
    PROFIT_TAKE,
    /** 진입 시점 밴드 하단 이탈(손절) — 물타기 없이 즉시. */
    RANGE_BREAKDOWN,
    /** 강한 악재(S1/S2) 등장 — 가격과 무관하게 즉시. */
    BAD_NEWS,
    /** 수동 청산. */
    MANUAL
}
