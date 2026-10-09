package com.cloudhandson.tossstock.autotrade;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * USD/KRW 환율 캐시(#886) — 모르면 null 을 주고 호출부가 US 매수를 보류한다.
 *
 * <p>Toss API 에 환율 엔드포인트가 없어 야후 {@code chart/KRW=X} 를 쓴다. 앱이 이미 같은
 * 엔드포인트로 지수 수익률({@code ValuationClient.getIndexReturnPct})을 받고 있어 새 의존성이
 * 아니다. 캐시 패턴도 {@code ValuationClient} 와 같게 맞춘다(성공/실패 TTL 분리).
 *
 * 설계: docs/design/886-us-fx-order-sizing/fn-usdKrw.md
 */
@Component
public class FxRateCache {

    private static final Logger log = LoggerFactory.getLogger(FxRateCache.class);
    private static final Duration TIMEOUT = Duration.ofSeconds(8);

    /**
     * 실측 최근 5일 1,339.1~1,343.8(변동 0.35%). 슬롯예산 60만원에서 0.35% 는 2,100원이라
     * 1주 단위 주문의 수량을 바꾸는 경우가 거의 없다. 틱이 1분이므로 1시간 캐시면 외부
     * 호출이 하루 24회로 묶인다.
     */
    private static final Duration SUCCESS_TTL = Duration.ofHours(1);
    /** 일시 장애에서 빨리 복구. ValuationClient 의 실패 TTL 과 같은 값. */
    private static final Duration FAILURE_TTL = Duration.ofMinutes(1);

    /**
     * 정상 범위 — 환율이 틀리면 주문 수량이 그 비율만큼 틀린다. 야후가 다른 심볼 값이나
     * 기본값을 주는 경우를 막는다. 지난 수십 년 USD/KRW 를 모두 포함하면서 자리수 오류
     * (134.078 / 13407.8)는 걸러내는 폭이다.
     */
    private static final BigDecimal MIN_RATE = BigDecimal.valueOf(500);
    private static final BigDecimal MAX_RATE = BigDecimal.valueOf(5000);

    private static final String KEY = "USDKRW";

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
    private final ObjectMapper om = new ObjectMapper();
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    private record Cached(BigDecimal value, Instant fetchedAt) {
        boolean expired() {
            Duration ttl = value == null ? FAILURE_TTL : SUCCESS_TTL;
            return Duration.between(fetchedAt, Instant.now()).compareTo(ttl) >= 0;
        }
    }

    /** USD/KRW. 조회 실패·응답 이상·값 비정상이면 <b>null</b>(예외를 던지지 않는다). */
    public BigDecimal usdKrw() {
        Cached c = cache.get(KEY);
        if (c != null && !c.expired()) {
            return c.value();
        }
        BigDecimal fresh = fetch();
        cache.put(KEY, new Cached(fresh, Instant.now()));
        return fresh;
    }

    private BigDecimal fetch() {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(
                            "https://query1.finance.yahoo.com/v8/finance/chart/KRW=X?range=5d&interval=1d"))
                    .header("User-Agent", "Mozilla/5.0")
                    .timeout(TIMEOUT)
                    .GET()
                    .build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() >= 400) {
                log.warn("환율 조회 실패: HTTP {}", res.statusCode());
                return null;
            }
            BigDecimal rate = lastClose(om.readTree(res.body()));
            if (rate == null) {
                log.warn("환율 조회 실패: 종가 없음");
                return null;
            }
            if (rate.compareTo(MIN_RATE) < 0 || rate.compareTo(MAX_RATE) > 0) {
                log.warn("환율이 정상 범위를 벗어남 — 사용하지 않음: {}", rate);
                return null;
            }
            log.info("USD/KRW 환율 갱신: {}", rate);
            return rate;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("환율 조회 중단");
            return null;
        } catch (Exception e) {
            log.warn("환율 조회 실패: {}", e.toString());
            return null;
        }
    }

    /** close 배열의 마지막 non-null 값. 구조가 다르면 null. */
    static BigDecimal lastClose(JsonNode root) {
        JsonNode closes = root.path("chart").path("result").path(0)
                .path("indicators").path("quote").path(0).path("close");
        if (!closes.isArray()) {
            return null;
        }
        for (int i = closes.size() - 1; i >= 0; i--) {
            JsonNode n = closes.get(i);
            if (n != null && n.isNumber()) {
                return n.decimalValue().setScale(2, java.math.RoundingMode.HALF_UP);
            }
        }
        return null;
    }
}
