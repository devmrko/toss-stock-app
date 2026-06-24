package com.cloudhandson.tossstock.toss;

import com.cloudhandson.tossstock.toss.dto.TossPrice;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 시세 캐시(stale-while-revalidate). 폴링(≈10s)마다 외부 토스 콜이 응답을 막던 문제 완화.
 * - 신선(≤TTL): 캐시 즉시 반환
 * - 낡음(>TTL): 캐시 즉시 반환 + 백그라운드 갱신(논블로킹)
 * - 없음/너무 낡음(>HARD): 동기 조회(첫 로드·장기 미사용 방지)
 */
@Component
public class PriceCache {

    private static final long TTL_MS = 8_000;     // 이보다 오래되면 백그라운드 갱신
    private static final long HARD_MS = 60_000;   // 이보다 오래되면 동기 갱신

    private final TossApiClient client;
    private final Map<String, Entry> cache = new ConcurrentHashMap<>();
    private final Set<String> inFlight = ConcurrentHashMap.newKeySet();
    private final ExecutorService refresher = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "price-refresh");
        t.setDaemon(true);
        return t;
    });

    public PriceCache(TossApiClient client) {
        this.client = client;
    }

    /** 현재 시각(ms). 테스트에서 오버라이드 가능. */
    long nowMs() {
        return System.currentTimeMillis();
    }

    public List<TossPrice> get(List<String> symbols) {
        long now = nowMs();
        List<String> cold = symbols.stream()
                .filter(s -> { Entry e = cache.get(s); return e == null || now - e.atMs > HARD_MS; })
                .toList();
        if (!cold.isEmpty()) {
            fetchInto(cold);   // 동기
        }
        long t = nowMs();
        List<String> stale = symbols.stream()
                .filter(s -> { Entry e = cache.get(s); return e != null && t - e.atMs > TTL_MS; })
                .toList();
        if (!stale.isEmpty()) {
            refreshAsync(stale);
        }
        return symbols.stream().map(cache::get).filter(Objects::nonNull)
                .map(e -> e.price).filter(Objects::nonNull).toList();
    }

    private void fetchInto(List<String> syms) {
        long now = nowMs();
        for (TossPrice p : client.getPrices(syms)) {
            cache.put(p.symbol(), new Entry(p, now));
        }
    }

    private void refreshAsync(List<String> syms) {
        String key = String.join(",", syms);
        if (!inFlight.add(key)) {
            return;   // 동일 묶음 갱신 중복 방지
        }
        refresher.submit(() -> {
            try {
                fetchInto(syms);
            } catch (RuntimeException ignore) {
                // 백그라운드 갱신 실패는 무시(다음 폴링에 재시도), 기존 캐시 유지
            } finally {
                inFlight.remove(key);
            }
        });
    }

    private static final class Entry {
        final TossPrice price;
        final long atMs;

        Entry(TossPrice price, long atMs) {
            this.price = price;
            this.atMs = atMs;
        }
    }
}
