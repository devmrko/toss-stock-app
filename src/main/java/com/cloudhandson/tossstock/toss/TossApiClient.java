package com.cloudhandson.tossstock.toss;

import com.cloudhandson.tossstock.toss.dto.TossAccount;
import com.cloudhandson.tossstock.toss.dto.TossCommission;
import com.cloudhandson.tossstock.toss.dto.TossBuyingPower;
import com.cloudhandson.tossstock.toss.dto.TossCandle;
import com.cloudhandson.tossstock.toss.dto.TossOrder;
import com.cloudhandson.tossstock.toss.dto.TossOrderRequest;
import com.cloudhandson.tossstock.toss.dto.TossPrice;
import com.cloudhandson.tossstock.toss.dto.TossStock;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.function.Supplier;
import java.util.zip.GZIPInputStream;

/**
 * 토스 Open API 읽기 호출(시세/종목/계좌). 응답은 {"result":[...]} 래퍼.
 * 설계: docs/design/409-skeleton-toss-api/README.md
 */
@Component
public class TossApiClient {

    private static final Logger log = LoggerFactory.getLogger(TossApiClient.class);
    private static final int HTTP_UNAUTHORIZED = 401;

    private final RestClient restClient;
    private final TossAuthClient auth;

    public TossApiClient(RestClient tossRestClient, TossAuthClient auth) {
        this.restClient = tossRestClient;
        this.auth = auth;
    }

    /**
     * 401 시 토큰 강제 재발급 후 1회만 재시도(#843, 2026-10-07 실사고). 토큰이 서버측에서
     * 먼저 무효화되면 로컬 캐시(TossAuthClient)는 모른 채 같은 토큰을 계속 써서 401이 반복됨
     * — 실측: #808 processHolding(손절 점검)이 한동안 401로 계속 실패, 재기동으로만 복구됨.
     * 401 이외(429 등)는 재시도 없이 그대로 전파(기존 동작 유지, 무한루프 방지 위해 최대 1회).
     */
    <T> T withAuthRetry(Supplier<T> call) {
        try {
            return call.get();
        } catch (TossApiException e) {
            if (e.getStatus() != HTTP_UNAUTHORIZED) {
                throw e;
            }
            log.warn("토스 API 401 — 토큰 강제 재발급 후 1회 재시도");
            auth.forceRefresh();
            return call.get();
        }
    }

    /** 시세 — GET /api/v1/prices?symbols=005930,000660 */
    public List<TossPrice> getPrices(List<String> symbols) {
        return withAuthRetry(() -> {
            PricesResponse body = restClient.get()
                    .uri(uri -> uri.path("/api/v1/prices").queryParam("symbols", String.join(",", symbols)).build())
                    .header("Authorization", "Bearer " + auth.getAccessToken())
                    .retrieve()
                    .onStatus(s -> s.value() >= 400, (req, res) -> {
                        throw new TossApiException("시세 조회 실패: HTTP " + res.getStatusCode().value(),
                                res.getStatusCode().value());
                    })
                    .body(PricesResponse.class);
            return body == null ? List.of() : body.result();
        });
    }

    /** 종목정보 — GET /api/v1/stocks?symbols=005930 */
    public List<TossStock> getStocks(List<String> symbols) {
        return withAuthRetry(() -> {
            StocksResponse body = restClient.get()
                    .uri(uri -> uri.path("/api/v1/stocks").queryParam("symbols", String.join(",", symbols)).build())
                    .header("Authorization", "Bearer " + auth.getAccessToken())
                    .retrieve()
                    .onStatus(s -> s.value() >= 400, (req, res) -> {
                        throw new TossApiException("종목 조회 실패: HTTP " + res.getStatusCode().value(),
                                res.getStatusCode().value());
                    })
                    .body(StocksResponse.class);
            return body == null ? List.of() : body.result();
        });
    }

    /** 계좌 목록 — GET /api/v1/accounts */
    public List<TossAccount> getAccounts() {
        return withAuthRetry(() -> {
            AccountsResponse body = restClient.get()
                    .uri("/api/v1/accounts")
                    .header("Authorization", "Bearer " + auth.getAccessToken())
                    .retrieve()
                    .onStatus(s -> s.value() >= 400, (req, res) -> {
                        throw new TossApiException("계좌 조회 실패: HTTP " + res.getStatusCode().value(),
                                res.getStatusCode().value());
                    })
                    .body(AccountsResponse.class);
            return body == null ? List.of() : body.result();
        });
    }

    /** 일봉 N개 — GET /api/v1/candles?symbol=005930&interval=1d&count=2 (0=오늘, 1=전일). */
    public List<TossCandle> getDailyCandles(String symbol, int count) {
        return withAuthRetry(() -> {
            CandlesResponse body = restClient.get()
                    .uri(uri -> uri.path("/api/v1/candles")
                            .queryParam("symbol", symbol)
                            .queryParam("interval", "1d")
                            .queryParam("count", count)
                            .build())
                    .header("Authorization", "Bearer " + auth.getAccessToken())
                    .retrieve()
                    .onStatus(s -> s.value() >= 400, (req, res) -> {
                        throw new TossApiException("캔들 조회 실패: HTTP " + res.getStatusCode().value(),
                                res.getStatusCode().value());
                    })
                    .body(CandlesResponse.class);
            return body == null || body.result() == null ? List.of() : body.result().candles();
        });
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record PricesResponse(List<TossPrice> result) {
    }

    /** 일봉 페이지 — before(직전 nextBefore, ISO+TZ) 이전 구간. count≤200. */
    public CandlePage getDailyCandlePage(String symbol, int count, String before) {
        return withAuthRetry(() -> {
            CandlesResponse body = restClient.get()
                    .uri(uri -> {
                        uri.path("/api/v1/candles").queryParam("symbol", symbol)
                                .queryParam("interval", "1d").queryParam("count", count);
                        if (before != null && !before.isBlank()) {
                            uri.queryParam("before", before);
                        }
                        return uri.build();
                    })
                    .header("Authorization", "Bearer " + auth.getAccessToken())
                    .retrieve()
                    .onStatus(s -> s.value() >= 400, (req, res) -> {
                        throw new TossApiException("캔들 페이지 조회 실패: HTTP " + res.getStatusCode().value(),
                                res.getStatusCode().value());
                    })
                    .body(CandlesResponse.class);
            if (body == null || body.result() == null) {
                return new CandlePage(List.of(), null);
            }
            return new CandlePage(body.result().candles(), body.result().nextBefore());
        });
    }

    /** 일봉 페이지 결과(다음 페이지 커서 포함). */
    public record CandlePage(List<TossCandle> candles, String nextBefore) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record CandlesResponse(CandlesResult result) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record CandlesResult(List<TossCandle> candles, String nextBefore) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record StocksResponse(List<TossStock> result) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record AccountsResponse(List<TossAccount> result) {
    }

    // ---- 계좌 식별 헤더(X-Tossinvest-Account) — accountSeq 사용(#802 실측으로 확정, accountNo 아님) ----

    /**
     * 에러 응답 본문 읽기 — Toss 응답이 gzip(Content-Encoding)으로 압축된 채 올 수 있어
     * (2026-10-01 실측: onStatus 콜백의 raw InputStream은 자동 압축해제가 안 됨) 헤더로 감지 후
     * 수동 압축해제. 실패해도 예외를 삼키고 빈 문자열(호출측 로그 품질 저하일 뿐, 흐름 차단 안 함).
     */
    private static String readErrorBody(ClientHttpResponse res) {
        try (InputStream raw = res.getBody()) {
            String encoding = res.getHeaders().getFirst("Content-Encoding");
            InputStream in = "gzip".equalsIgnoreCase(encoding) ? new GZIPInputStream(raw) : raw;
            return StreamUtils.copyToString(in, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "(응답 본문 읽기 실패: " + e.getMessage() + ")";
        }
    }

    private String firstAccountSeq() {
        List<TossAccount> accounts = getAccounts();
        if (accounts.isEmpty()) {
            throw new TossApiException("등록된 계좌가 없음", 0);
        }
        return String.valueOf(accounts.get(0).accountSeq());
    }

    /** 예수금(매수가능금액) — GET /api/v1/buying-power?currency=KRW|USD. 설계: docs/design/802-toss-buying-power/README.md */
    public TossBuyingPower getBuyingPower(String currency) {
        return withAuthRetry(() -> {
            BuyingPowerResponse body = restClient.get()
                    .uri(uri -> uri.path("/api/v1/buying-power").queryParam("currency", currency).build())
                    .header("Authorization", "Bearer " + auth.getAccessToken())
                    .header("X-Tossinvest-Account", firstAccountSeq())
                    .retrieve()
                    .onStatus(s -> s.value() >= 400, (req, res) -> {
                        throw new TossApiException("예수금 조회 실패: HTTP " + res.getStatusCode().value(),
                                res.getStatusCode().value());
                    })
                    .body(BuyingPowerResponse.class);
            return body == null ? null : body.result();
        });
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record BuyingPowerResponse(TossBuyingPower result) {
    }

    /**
     * 수수료 요율표 — GET /api/v1/commissions (#863).
     * 요율을 코드에 박아두면 바뀌는 순간 조용히 틀려지고, 그 틀림은 실현손익 집계에서
     * 뒤늦게 드러난다. {@code CommissionRateCache} 가 1일 캐시로 감싸 쓴다.
     */
    public List<TossCommission> getCommissions() {
        return withAuthRetry(() -> {
            CommissionsResponse body = restClient.get()
                    .uri("/api/v1/commissions")
                    .header("Authorization", "Bearer " + auth.getAccessToken())
                    .header("X-Tossinvest-Account", firstAccountSeq())
                    .retrieve()
                    .onStatus(s -> s.value() >= 400, (req, res) -> {
                        throw new TossApiException("수수료 요율 조회 실패: HTTP " + res.getStatusCode().value(),
                                res.getStatusCode().value());
                    })
                    .body(CommissionsResponse.class);
            return body == null || body.result() == null ? List.<TossCommission>of() : body.result();
        });
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record CommissionsResponse(List<TossCommission> result) {
    }

    // ---- 주문(#808) — 2026-09-29 해성디에스(195870) 1주 매수/매도 실주문으로 스키마 검증 완료. ----

    /** 주문 접수 — POST /api/v1/orders. 설계: docs/design/808-auto-trade-engine/fn-order-executor.md */
    public TossOrder placeOrder(TossOrderRequest req) {
        return withAuthRetry(() -> {
            OrderResponse body = restClient.post()
                    .uri("/api/v1/orders")
                    .header("Authorization", "Bearer " + auth.getAccessToken())
                    .header("X-Tossinvest-Account", firstAccountSeq())
                    .body(req)
                    .retrieve()
                    .onStatus(s -> s.value() >= 400, (r, res) -> {
                        String responseBody = readErrorBody(res);
                        log.warn("주문 접수 실패: HTTP {} 요청={} 응답={}", res.getStatusCode().value(), req, responseBody);
                        throw new TossApiException(
                                "주문 접수 실패: HTTP " + res.getStatusCode().value() + " " + responseBody,
                                res.getStatusCode().value());
                    })
                    .body(OrderResponse.class);
            return body == null ? null : body.result();
        });
    }

    /** 주문 취소 — POST /api/v1/orders/{orderId}/cancel. */
    public void cancelOrder(String orderId) {
        withAuthRetry(() -> {
            restClient.post()
                    .uri("/api/v1/orders/{orderId}/cancel", orderId)
                    .header("Authorization", "Bearer " + auth.getAccessToken())
                    .header("X-Tossinvest-Account", firstAccountSeq())
                    .retrieve()
                    .onStatus(s -> s.value() >= 400, (r, res) -> {
                        throw new TossApiException("주문 취소 실패: HTTP " + res.getStatusCode().value(),
                                res.getStatusCode().value());
                    })
                    .toBodilessEntity();
            return null;
        });
    }

    /** 주문 상세 조회 — GET /api/v1/orders/{orderId}. */
    public TossOrder getOrder(String orderId) {
        return withAuthRetry(() -> {
            OrderResponse body = restClient.get()
                    .uri("/api/v1/orders/{orderId}", orderId)
                    .header("Authorization", "Bearer " + auth.getAccessToken())
                    .header("X-Tossinvest-Account", firstAccountSeq())
                    .retrieve()
                    .onStatus(s -> s.value() >= 400, (r, res) -> {
                        throw new TossApiException("주문 조회 실패: HTTP " + res.getStatusCode().value(),
                                res.getStatusCode().value());
                    })
                    .body(OrderResponse.class);
            return body == null ? null : body.result();
        });
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record OrderResponse(TossOrder result) {
    }
}
