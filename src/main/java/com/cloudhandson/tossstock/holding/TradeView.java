package com.cloudhandson.tossstock.holding;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 거래 1건(집계행 펼침용). (#433) */
public record TradeView(
        Long id,
        String side,            // BUY | SELL
        LocalDateTime tradedAt,
        BigDecimal price,
        Long quantity,
        Double returnPct        // 현재가 대비 체결가 변동 %(참고)
) {
}
