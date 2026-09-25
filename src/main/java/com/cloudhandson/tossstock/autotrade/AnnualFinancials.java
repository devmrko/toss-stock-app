package com.cloudhandson.tossstock.autotrade;

import java.math.BigDecimal;
import java.util.List;

/**
 * 연도별 실적(최근 실제 결산 연도만, 컨센서스/추정 연도 제외). 오름차순(과거→최근) 정렬.
 * 설계: docs/design/808-auto-trade-engine/fn-fundamental-checklist.md
 */
public record AnnualFinancials(List<Year> years) {

    public record Year(String label, BigDecimal revenue, BigDecimal operatingProfit,
                        BigDecimal netIncome, BigDecimal debtRatio, BigDecimal dividendPerShare) {
    }

    public Year latest() {
        return years.isEmpty() ? null : years.get(years.size() - 1);
    }
}
