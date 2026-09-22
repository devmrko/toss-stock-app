package com.cloudhandson.tossstock.autotrade;

/** 매도 사유. 설계: docs/design/808-auto-trade-engine/fn-trailing-stop.md */
public enum ExitReason {
    NONE,
    HARD_STOP,
    TRAIL_STOP,
    NEWS_FADED,
    CIRCUIT_BREAKER,
    MANUAL
}
