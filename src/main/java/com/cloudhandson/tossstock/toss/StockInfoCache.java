package com.cloudhandson.tossstock.toss;

import com.cloudhandson.tossstock.toss.dto.TossStock;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 종목 정보(이름) 캐시. 종목명은 사실상 불변이라 세션 동안 유지(미보유 심볼만 1회 조회).
 * 폴링마다 반복되던 /stocks 콜을 워밍업 후 제거.
 */
@Component
public class StockInfoCache {

    private final TossApiClient client;
    private final Map<String, TossStock> cache = new ConcurrentHashMap<>();

    public StockInfoCache(TossApiClient client) {
        this.client = client;
    }

    public List<TossStock> get(List<String> symbols) {
        List<String> missing = symbols.stream().filter(s -> !cache.containsKey(s)).toList();
        if (!missing.isEmpty()) {
            for (TossStock s : client.getStocks(missing)) {
                cache.put(s.symbol(), s);
            }
        }
        List<TossStock> out = new ArrayList<>();
        for (String s : symbols) {
            TossStock v = cache.get(s);
            if (v != null) {
                out.add(v);
            }
        }
        return out;
    }
}
