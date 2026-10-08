package com.cloudhandson.tossstock.autotrade;

/**
 * 원칙 §4 "지수 하락기 보정 — 지수 대비 -10% 언더퍼폼 시 교체 고려"(순수 함수, #873).
 * 설계: docs/design/873-principle-sell-rules-completion/README.md §2.2
 *
 * <p>원칙이 손절은 "필수 하드룰"로, 이 조항은 "교체 <b>고려</b>"로 쓰고 있다. 문서 스스로
 * 강제성을 구분하므로 이 판정은 <b>알림</b>에만 쓰고 자동 매도에는 쓰지 않는다.
 */
public final class IndexLagChecker {

    private IndexLagChecker() {
    }

    /**
     * 두 조건을 모두 충족하는가.
     * <ol>
     *   <li><b>지수 하락기</b> — {@code indexReturnPct < 0}. 원칙이 "지수 <b>하락기</b>에는 …"
     *       으로 조건부로 쓰고 있다. 상승장에서 뒤처지는 것만으로는 교체 사유가 아니다.</li>
     *   <li><b>언더퍼폼</b> — 종목 수익률이 지수보다 {@code thresholdPct}%p 이상 낮다.</li>
     * </ol>
     *
     * @param stockReturnPct 종목 수익률(%). null 허용 — 데이터 부족
     * @param indexReturnPct 지수 수익률(%). null 허용 — 데이터 부족
     * @param thresholdPct   언더퍼폼 임계(양수, 예: 10.0 = -10%p)
     * @return 데이터가 부족하면 false — 알림은 근거가 확실할 때만 보낸다
     */
    public static boolean lagsIndex(Double stockReturnPct, Double indexReturnPct, double thresholdPct) {
        if (stockReturnPct == null || indexReturnPct == null) {
            return false;
        }
        if (indexReturnPct >= 0) {
            return false;   // 지수 상승기 — 원칙의 전제 조건 불충족
        }
        return stockReturnPct - indexReturnPct <= -Math.abs(thresholdPct);
    }
}
