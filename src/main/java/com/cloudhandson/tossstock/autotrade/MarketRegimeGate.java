package com.cloudhandson.tossstock.autotrade;

/**
 * 시장상황(breadth) 기반 신규매수 허용 여부 판정(순수 함수).
 * 설계: docs/design/808-auto-trade-engine/fn-market-regime-gate.md
 * 2026-10-01: MARKET 타겟 S1(강한악재) 뉴스 카운트를 하드 AND-게이트에서 제외 —
 * 사용자 지적: 이 뉴스들("코스피 3거래일 연속 하락", "외국인 팔자 행렬")은 대부분
 * 이미 일어난 가격 하락을 사후 보도하는 후행(lagging) 지표라, 선행(leading) 신호가
 * 아닌 걸 "매수 금지"의 필수(mandatory) 조건으로 쓰는 건 맞지 않음 — breadth 자체가
 * 이미 "가격이 얼마나 빠졌는지"를 수치로 반영하고 있어 중복이기도 함. breadth만으로 판정.
 */
public final class MarketRegimeGate {

    private MarketRegimeGate() {
    }

    /**
     * @param breadthPct 0~100. 해당 시장의 상승비율.
     * @param sampleSize 집계에 들어간 종목 수. <b>최소치 미만이면 '신호 없음'으로 보고
     *                   통과시킨다</b>(#879) — 7종목짜리 breadth 로 실매매를 막을 수 없고,
     *                   KR 분포(최근 25거래일 16.3~69.9%, 중앙값 ≈45%)로 캘리브레이션한
     *                   임계값을 표본이 다른 시장에 적용할 근거도 없다.
     *                   표본 수로 판단하므로 커버리지가 늘면 코드 변경 없이 게이트가 켜진다.
     */
    public static boolean evaluate(int breadthPct, int sampleSize, AutoTradeProperties.Gate props) {
        if (breadthPct < 0 || breadthPct > 100) {
            throw new IllegalArgumentException("breadthPct must be in [0,100]: " + breadthPct);
        }
        if (sampleSize < props.minBreadthSample()) {
            return true;   // 신호 없음 → 중립(통과)
        }
        return breadthPct >= props.minBreadthPct();
    }
}
