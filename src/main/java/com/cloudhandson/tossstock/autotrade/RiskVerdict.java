package com.cloudhandson.tossstock.autotrade;

/**
 * 탐지된 리스크 사건과 그 근거 기사(#872).
 * 설계: docs/design/872-risk-event-exit/README.md §3
 *
 * @param flag  리스크 종류 — DILUTION/GOVERNANCE/DELISTING/BLOCKDEAL/LITIGATION/LOSS
 * @param title 근거 기사 제목(매도 사유 문자열에 남긴다)
 */
public record RiskVerdict(String flag, String title) {
}
