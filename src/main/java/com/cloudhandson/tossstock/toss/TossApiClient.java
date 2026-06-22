package com.cloudhandson.tossstock.toss;

import com.cloudhandson.tossstock.toss.dto.TossAccount;
import com.cloudhandson.tossstock.toss.dto.TossCandle;
import com.cloudhandson.tossstock.toss.dto.TossPrice;
import com.cloudhandson.tossstock.toss.dto.TossStock;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;

/**
 * 토스 Open API 읽기 호출(시세/종목/계좌). 응답은 {"result":[...]} 래퍼.
 * 설계: docs/design/409-skeleton-toss-api/README.md
 */
@Component
public class TossApiClient {

    private final RestClient restClient;
    private final TossAuthClient auth;

    public TossApiClient(RestClient tossRestClient, TossAuthClient auth) {
        this.restClient = tossRestClient;
        this.auth = auth;
    }

    /** 시세 — GET /api/v1/prices?symbols=005930,000660 */
    public List<TossPrice> getPrices(List<String> symbols) {
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
    }

    /** 종목정보 — GET /api/v1/stocks?symbols=005930 */
    public List<TossStock> getStocks(List<String> symbols) {
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
    }

    /** 계좌 목록 — GET /api/v1/accounts */
    public List<TossAccount> getAccounts() {
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
    }

    /** 일봉 N개 — GET /api/v1/candles?symbol=005930&interval=1d&count=2 (0=오늘, 1=전일). */
    public List<TossCandle> getDailyCandles(String symbol, int count) {
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
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record PricesResponse(List<TossPrice> result) {
    }

    /** 일봉 페이지 — before(직전 nextBefore, ISO+TZ) 이전 구간. count≤200. */
    public CandlePage getDailyCandlePage(String symbol, int count, String before) {
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
}
