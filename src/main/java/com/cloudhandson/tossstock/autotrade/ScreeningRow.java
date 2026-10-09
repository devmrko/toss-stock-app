package com.cloudhandson.tossstock.autotrade;

import java.math.BigDecimal;

/**
 * 종목 1건의 로컬 집계 — {@link UniverseScreener} 의 순수 입력(#887).
 * {@code daily_ohlcv} 만으로 만들며 외부 API 를 쓰지 않는다.
 * 설계: docs/design/887-news-exclusion-filter/README.md §6
 *
 * @param symbol         종목코드
 * @param market         KR / US
 * @param latestClose    최신 거래일 종가
 * @param closeBefore20  20거래일 전 종가(상대강세 분모). 결측이면 상대강세 판정 불가
 * @param avgTurnover20  최근 20거래일 평균 거래대금(종가 x 거래량)
 * @param lowRecent      최근 {@code extension-lookback-days} 거래일 최저 종가(급등률 분모).
 *                       창 길이를 매수 게이트({@link PriceExtension})와 같은 설정값으로 묶어
 *                       발굴과 진입이 같은 기준을 쓰게 한다
 * @param bars           집계에 쓰인 일봉 개수
 */
public record ScreeningRow(String symbol, String market, BigDecimal latestClose,
                            BigDecimal closeBefore20, BigDecimal avgTurnover20,
                            BigDecimal lowRecent, int bars) {
}
