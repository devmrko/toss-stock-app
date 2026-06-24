package com.cloudhandson.tossstock.holding;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/** 종목 단위 집계(평균단가) + 지표(응답). (#433) */
public record PositionView(
        String symbol,
        String name,
        String sector,
        Long netQty,               // 순보유 수량(Σ매수−Σ매도). 폴백 시 null
        BigDecimal avgCost,        // 평균 매수단가
        BigDecimal currentPrice,
        Double change1dPct,        // 1일 등락(전일 종가 대비)
        Double change7dPct,        // 7일 등락(7일 전 종가 대비)
        Double unrealizedPct,      // (현재−평단)/평단 %
        Long unrealizedAmount,     // 순수량×(현재−평단)
        Long realizedAmount,       // Σ매도수량×(매도가−평단)
        Long daysHeld,             // 최초 매수일 이후
        Double stopPct,
        BigDecimal stopPrice,      // 평단×(1−N%)
        Double stopDistPct,
        boolean belowStop,
        BigDecimal peakSinceBuy,
        Double ddFromPeak,
        boolean stopHitSinceBuy,
        LocalDateTime firstBuyAt,
        String broker,             // 매매처(중복 제거 결합) (#441)
        List<TradeView> trades     // 거래내역(최신순)
) {
}
