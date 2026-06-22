package com.cloudhandson.tossstock.toss;

import com.cloudhandson.tossstock.toss.dto.TossCandle;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** 종목별 일봉 캐시(TTL 60s) — 거래량/전일종가 콜 절감. 실패는 캐시하지 않음. */
@Component
public class CandleCache {

    private static final long TTL_SEC = 60;

    private final TossApiClient client;
    private final Map<String, Entry> cache = new ConcurrentHashMap<>();

    public CandleCache(TossApiClient client) {
        this.client = client;
    }

    public List<TossCandle> get(String symbol) {
        long now = Instant.now().getEpochSecond();
        Entry e = cache.get(symbol);
        if (e != null && now - e.atSec < TTL_SEC) {
            return e.candles;
        }
        List<TossCandle> fresh = client.getDailyCandles(symbol, 2);
        cache.put(symbol, new Entry(fresh, now));
        return fresh;
    }

    private record Entry(List<TossCandle> candles, long atSec) {
    }
}
