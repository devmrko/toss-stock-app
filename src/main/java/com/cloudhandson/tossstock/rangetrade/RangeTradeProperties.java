package com.cloudhandson.tossstock.rangetrade;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;

/**
 * 레인지(박스권) 스윙매매 설정 — 값은 .env/application.yml 경유(#818, 모멘텀 #808과 완전 분리).
 * dryRun 기본값은 application.yml 에서 true 로 고정(이중 안전장치의 코드측 절반).
 * 설계: docs/design/818-range-trade-swing/README.md §6, §12
 */
@ConfigurationProperties(prefix = "range-trade")
public record RangeTradeProperties(
        boolean dryRun,
        BigDecimal totalBudget,
        int maxSymbols,
        BigDecimal perSymbolBudget,
        int windowDays,
        double minWidthPct,
        double maxWidthPct,
        double maxTrendDriftPct,
        double entryZonePct,
        double exitZonePct,
        double breakdownPct,
        BigDecimal minAvgTradingValue,
        int holdingHorizonDays,
        String webhookUrl,
        String cron) {

    public boolean alertsEnabled() {
        return webhookUrl != null && !webhookUrl.isBlank();
    }

    /** 밴드 판정에 필요한 최소 일봉 개수 — fn-range-bound-checker.md §3 "windowDays+1개 이상". */
    public int requiredBars() {
        return windowDays + 1;
    }
}
