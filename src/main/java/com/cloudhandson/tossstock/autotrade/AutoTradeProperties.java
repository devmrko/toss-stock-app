package com.cloudhandson.tossstock.autotrade;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;

import java.math.BigDecimal;

/**
 * 자동매매 설정 — 값은 .env/application.yml 경유.
 * dryRun 기본값은 application.yml 에서 true 로 고정(§9 이중 안전장치의 코드측 절반).
 * 설계: docs/design/808-auto-trade-engine/README.md §4, §9
 */
@ConfigurationProperties(prefix = "auto-trade")
public record AutoTradeProperties(
        boolean dryRun,
        BigDecimal totalBudget,
        int maxSymbols,
        BigDecimal perSymbolBudget,
        double circuitBreakerPct,
        double hardStopPct,
        double trailStopPct,
        String webhookUrl,
        String cron,
        int volumeSpikeWindowDays,
        double volumeSpikeMultiplier,
        @NestedConfigurationProperty Gate gate) {

    public boolean alertsEnabled() {
        return webhookUrl != null && !webhookUrl.isBlank();
    }

    /** 시장상황 게이트 임계값 — "운영하면서 조정" 대상(하드코딩 금지). */
    public record Gate(int minBreadthPct, int maxS1Count, int lookbackDays) {
    }
}
