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
        double catalystMaxExtensionPct,
        int screenTopN,
        String screenerMarkets,
        int riskExclusionDays,
        double catalystValuationMultiple,
        double multibaggerGainPct,
        double multibaggerTrailStopPct,
        double indexLagAlertPct,
        String weeklyReviewCron,
        @NestedConfigurationProperty Gate gate) {

    public boolean alertsEnabled() {
        return webhookUrl != null && !webhookUrl.isBlank();
    }

    /*
     * #887 신규 4개 — 설계: docs/design/887-news-exclusion-filter/README.md §8
     *
     * catalystMaxExtensionPct: 재평가 촉매가 급등 필터를 통째로 면제하던 구멍의 상한.
     *   촉매일 조건부 측정(N=2,149)에서 급등 +10~15% 구간의 하드손절률이 71%,
     *   +25% 이상은 89.9% 였다 — 어떤 촉매도 그 확률을 정당화하지 못한다.
     *
     * screenTopN: 스크리너 등록 상한. API 비용을 결정론적으로 묶는 장치다. 실측으로
     *   상위 40 중 POPULARITY 통과가 중위 8종목이고, 밸류에이션·재무 API 는 그 뒤에만
     *   호출되므로 틱당 외부 요청이 통제된다. 0 이면 후보 0건(킬스위치).
     *
     * screenerMarkets: 활성 시장 CSV. US 는 일봉 거래량 결함(#885, 최신/20일평균 중위
     *   0.125배)으로 거래대금 기반 유동성 필터가 전멸하고 환전도 미적용(#886)이라
     *   기본값에서 제외한다. 빈 값이면 후보 0건(킬스위치).
     *
     * riskExclusionDays: 리스크 기사 배제 창(일). RiskEventDetector 의 24시간보다 길게
     *   잡는다 — 매수 배제의 오탐은 기회비용뿐이고 매도 오탐은 실현손실이라 비대칭이다.
     */

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
