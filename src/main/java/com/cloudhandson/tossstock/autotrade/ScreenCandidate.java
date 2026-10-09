package com.cloudhandson.tossstock.autotrade;

import java.math.BigDecimal;

/**
 * 스크리너를 통과한 후보 1건(#887) — 순수 출력.
 * 설계: docs/design/887-news-exclusion-filter/README.md §6
 *
 * @param turnover         20일 평균 거래대금. <b>2차 정렬 키</b>(내림차순)
 * @param extensionPct     5거래일 저점 대비 상승률(%). <b>1차 정렬 키</b>(오름차순) —
 *                         근거는 {@link UniverseScreener#screen} 6단계 주석
 * @param excessReturnPct  20거래일 수익률 − 지수 20거래일 수익률(%p)
 */
public record ScreenCandidate(String symbol, String market, BigDecimal turnover,
                               double extensionPct, double excessReturnPct) {
}
