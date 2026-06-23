package com.cloudhandson.tossstock.holding;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 보유 1건 + 계산 지표(응답). */
public record HoldingView(
        Long id,
        String symbol,
        String name,
        String sector,
        LocalDateTime buyAt,
        BigDecimal buyPrice,
        Long quantity,
        Double stopPct,
        String memo,
        BigDecimal currentPrice,
        Double returnPct,        // (현재-매수)/매수 %
        Long returnAmount,       // (현재-매수)×수량
        Long daysHeld,
        BigDecimal stopPrice,    // 매수가×(1−N%)
        Double stopDistPct,      // 현재가 대비 스탑까지 여유 %(음수=이탈)
        boolean belowStop,       // 현재가 ≤ 스탑가
        BigDecimal peakSinceBuy, // 매수 후 고점
        Double ddFromPeak,       // 고점대비 현재 낙폭 %
        boolean stopHitSinceBuy) // 보유 중 저가가 스탑 터치 이력
{
}
