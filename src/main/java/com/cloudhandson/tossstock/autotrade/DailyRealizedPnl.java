package com.cloudhandson.tossstock.autotrade;

import java.math.BigDecimal;

/**
 * 날짜별 실현손익 집계 행(#851) — 토스 앱의 일별 보기와 직접 대조하기 위함. 전체 누적
 * 합계만 보여주면 "오늘 하루" 숫자와 비교할 때 다른 날짜 거래가 섞여 안 맞아 보이는
 * 혼동이 생긴다(2026-10-08 실사례). SQL이 집계까지 전부 수행 — 가공 없이 그대로 직렬화.
 */
public record DailyRealizedPnl(String exitDate, int trades, BigDecimal grossPnl, BigDecimal fees,
                                BigDecimal netPnl) {
}
