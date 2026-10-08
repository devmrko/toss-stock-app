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
        double priceMovePct,
        double maxPer,
        double maxPbr,
        double maxDebtRatio,
        BigDecimal minAvgTradingValue,
        BigDecimal minAvgTradingValueUsd,
        int relativeStrengthWindowDays,
        int minFundamentalPass,
        int candidateMaxRetentionDays,
        int newsFadedCooldownMinutes,
        int stopExitCooldownMinutes,
        double maxExtensionPct,
        int extensionLookbackDays,
        double catalystValuationMultiple,
        double multibaggerGainPct,
        double multibaggerTrailStopPct,
        double indexLagAlertPct,
        String weeklyReviewCron,
        @NestedConfigurationProperty Gate gate) {

    public boolean alertsEnabled() {
        return webhookUrl != null && !webhookUrl.isBlank();
    }

    /**
     * 시장상황 게이트 임계값 — "운영하면서 조정" 대상(하드코딩 금지).
     *
     * @param minBreadthSample breadth 집계 최소 표본(#879). 미만이면 '신호 없음'으로 보고
     *                         통과시킨다. 기본 100 — 기존 breadth(minCoverage=100) 와 같은 수치를
     *                         써서 새 임계값을 발명하지 않는다.
     * @param indexVolSigma US 레짐 임계의 시그마 배수(#883). SPY 일간 수익률이
     *                      {@code -(sigma x 실현변동성)} 미만이면 US 매수를 막는다.
     *                      고정 %% 임계는 연도별 차단율이 통제되지 않았다(실측: SPY
     *                      0.8~15.9%%, KOSPI 1.7~23.1%%). 정규화 시 1.3%% 내외로 안정된다.
     * @param indexVolWindowDays 실현변동성 창(거래일). 이것만 있으면 되므로 긴 캘리브레이션
     *                      창이 불필요하다.
     * @param indexVolFloorPct 변동성 하한. 변동성이 비정상적으로 0 에 가까우면 임계가 0 에
     *                      붙어 아무 하락일이나 차단하는 과민 상태가 된다. 실측 최저가
     *                      2017년 US 0.43%% 이므로 0.2 하한은 정상 범위를 건드리지 않는다.
     */
    public record Gate(int minBreadthPct, int minBreadthSample,
                        double indexVolSigma, int indexVolWindowDays, double indexVolFloorPct) {
    }
}
