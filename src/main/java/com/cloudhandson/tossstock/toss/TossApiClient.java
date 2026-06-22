package com.cloudhandson.tossstock.toss;

import com.cloudhandson.tossstock.toss.dto.TossAccount;
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

    @JsonIgnoreProperties(ignoreUnknown = true)
    record PricesResponse(List<TossPrice> result) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record StocksResponse(List<TossStock> result) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record AccountsResponse(List<TossAccount> result) {
    }
}
