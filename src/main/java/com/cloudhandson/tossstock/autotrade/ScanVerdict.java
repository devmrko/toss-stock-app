package com.cloudhandson.tossstock.autotrade;

/**
 * 매수 스캔 판정 1건(#884). 설계: docs/design/884-scan-verdict-diagnostics/README.md
 *
 * <p>왜 필요한가: {@code scanCandidates} 의 탈락 지점이 12개인데 전부 무로그였다.
 * 그래서 "TSM 이 어디서 막혔나", "US 가 왜 한 번도 매수하지 않았나"를 추측으로만
 * 답해야 했다. 계측이 없으면 게이트 질문이 외부 지수 프록시 추정으로 되돌아간다.
 *
 * @param symbol 대상 종목. tick 조기 반환이면 null
 * @param market KR/US. tick 조기 반환이면 null
 * @param stage  어느 게이트에서 끝났는지(집계용 짧은 식별자). 매수 성공은 {@code BUY}
 * @param detail 사람이 읽을 근거(수치 포함)
 */
public record ScanVerdict(String symbol, String market, String stage, String detail) {

    static ScanVerdict of(AutoTradeCandidate c, String stage, String detail) {
        return new ScanVerdict(c.getSymbol(), c.getMarket(), stage, detail);
    }

    /** tick 조기 반환 — 후보에 도달조차 못한 경우. */
    static ScanVerdict tick(String stage, String detail) {
        return new ScanVerdict(null, null, stage, detail);
    }
}
