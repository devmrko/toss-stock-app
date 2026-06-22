package com.cloudhandson.tossstock.toss;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;

/**
 * 토스 OAuth2 client_credentials 토큰 발급/캐시.
 * 설계: docs/design/409-skeleton-toss-api/fn-toss-auth.md
 */
@Component
public class TossAuthClient {

    private final RestClient restClient;
    private final TossProperties props;
    private volatile TossToken cache;

    public TossAuthClient(RestClient tossRestClient, TossProperties props) {
        this.restClient = tossRestClient;
        this.props = props;
    }

    /** 캐시가 유효하면 재사용, 아니면 재발급. 이중 발급 방지를 위해 동기화. */
    public synchronized String getAccessToken() {
        long now = Instant.now().getEpochSecond();
        if (cache == null || cache.isExpired(now)) {
            cache = requestToken();
        }
        return cache.accessToken();
    }

    TossToken requestToken() {
        String raw = props.clientKey() + ":" + props.secretKey();
        String basic = Base64.getEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8));

        TokenResponse body = restClient.post()
                .uri("/oauth2/token")
                .header("Authorization", "Basic " + basic)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body("grant_type=client_credentials")
                .retrieve()
                .onStatus(s -> s.value() >= 400, (req, res) -> {
                    throw new TossApiException("토큰 발급 실패: HTTP " + res.getStatusCode().value(),
                            res.getStatusCode().value());
                })
                .body(TokenResponse.class);

        if (body == null || body.accessToken() == null) {
            throw new TossApiException("토큰 응답이 비어 있음", 0);
        }
        long expiresAt = Instant.now().getEpochSecond() + body.expiresIn();
        return new TossToken(body.accessToken(), body.tokenType(), expiresAt);
    }

    record TokenResponse(
            @JsonProperty("access_token") String accessToken,
            @JsonProperty("token_type") String tokenType,
            @JsonProperty("expires_in") long expiresIn) {
    }
}
