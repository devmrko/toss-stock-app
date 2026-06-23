package com.cloudhandson.tossstock.watchlist;

import java.math.BigDecimal;

/** 워치리스트 한 종목의 모니터링 행. */
public record WatchlistQuote(
        Long id,
        String symbol,
        String name,
        String sector,       // 섹터(섹터 뉴스 뱃지용), 없으면 null
        BigDecimal lastPrice,
        BigDecimal prevClose,
        BigDecimal changeAmount,
        Double changeRate,   // % (전일종가 대비), 없으면 null
        Long volume,         // 당일 누적 거래량
        String currency,
        int rank,            // 거래량 순위(1..), assemble 정렬 후 주입
        boolean stale) {     // 부분 조회 실패 표시

    public WatchlistQuote withRank(int newRank) {
        return new WatchlistQuote(id, symbol, name, sector, lastPrice, prevClose, changeAmount,
                changeRate, volume, currency, newRank, stale);
    }

    public WatchlistQuote withStale() {
        return new WatchlistQuote(id, symbol, name, sector, lastPrice, prevClose, changeAmount,
                changeRate, volume, currency, rank, true);
    }
}
