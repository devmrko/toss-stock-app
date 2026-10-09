package com.cloudhandson.tossstock.autotrade;

/**
 * 스크리너 단계별 잔존 수(#887) — 판정이 아니라 <b>관측</b>용. 인수조건 AC8.
 *
 * <p>#884 의 {@link ScanVerdict} 가 "왜 이 후보를 안 샀는가"를 설명하는 것과 같은 이유로,
 * 이쪽은 "왜 이 종목이 후보가 못 됐는가"를 숫자로 남긴다. 단계 순서는 2026-10-08 실측
 * (전체 3,472 → 유동성 1,769 → 상대강세 1,243 → 급등률 679 → 선정 40)과 같게 고정해
 * 배포 후 대조할 수 있게 둔다.
 *
 * 설계: docs/design/887-news-exclusion-filter/README.md §6, §12
 */
public record ScreenFunnel(int total, int afterBars, int afterLiquidity,
                            int afterRelativeStrength, int afterExtension,
                            int afterRiskExclusion, int selected) {

    public static ScreenFunnel empty() {
        return new ScreenFunnel(0, 0, 0, 0, 0, 0, 0);
    }

    /** 로그 1줄(AC8). 시장 라벨은 호출부가 붙인다. */
    public String describe(double maxExtensionPct, int topN) {
        return String.format(
                "전체 %d → 바21+ %d → 유동성 %d → 상대강세 %d → 급등<%.1f%% %d → 리스크배제 %d → 선정 %d (상한 %d)",
                total, afterBars, afterLiquidity, afterRelativeStrength,
                maxExtensionPct, afterExtension, afterRiskExclusion, selected, topN);
    }
}
