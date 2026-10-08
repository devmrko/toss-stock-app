package com.cloudhandson.tossstock.autotrade;

/**
 * 지수의 일간 수익률 + 최근 실현변동성(#883). 둘 다 같은 종가 조회 1회로 계산된다.
 * 설계: docs/design/883-vol-normalized-regime-threshold/README.md
 *
 * @param dailyPct 최근 거래일 수익률(%)
 * @param volPct   최근 N거래일 일간수익률 표준편차(%)
 */
public record IndexRiskSignal(double dailyPct, double volPct) {
}
