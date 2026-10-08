package com.cloudhandson.tossstock.autotrade;

/** 매도 사유. 설계: docs/design/808-auto-trade-engine/fn-trailing-stop.md */
public enum ExitReason {
    NONE,
    HARD_STOP,
    TRAIL_STOP,
    /** #871 제거됨 — 기사 만료 기반 매도. 과거 기록(exit_reason) 조회를 위해 값만 남긴다. */
    NEWS_FADED,
    /** #872 투자논거 무효 — 진입 이후 리스크 기사(유증·횡령·상폐·블록딜·소송·적자) 발생. */
    RISK_EVENT,
    CIRCUIT_BREAKER,
    MANUAL
}
