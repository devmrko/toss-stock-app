package com.cloudhandson.tossstock.toss;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * #843 실사고(2026-10-07) 재발 방지 — withAuthRetry가 401에서만 토큰을 강제 재발급하고
 * 정확히 1회 재시도하는지 검증. RestClient 플루언트 체인은 실제 호출 경로가 아니라 이
 * 래퍼 함수 자체(패키지 전용으로 공개)를 직접 호출해 검증한다 — RestClient 전체를 목으로
 * 흉내 내는 건 깨지기 쉽고, 실제 와이어링 확인은 TossApiClientIT(통합 테스트)가 담당한다.
 * 설계: docs/design/843-toss-token-retry/README.md §10
 */
class TossApiClientRetryTest {

    private final TossAuthClient auth = mock(TossAuthClient.class);
    private final TossApiClient client = new TossApiClient(mock(org.springframework.web.client.RestClient.class), auth);

    @Test
    void succeeds_without_refresh_when_first_call_succeeds() {
        Supplier<String> call = () -> "ok";

        String result = client.withAuthRetry(call);

        assertThat(result).isEqualTo("ok");
        verify(auth, never()).forceRefresh();
    }

    @Test
    void retries_exactly_once_after_force_refresh_on_401() {
        AtomicInteger calls = new AtomicInteger();
        Supplier<String> call = () -> {
            if (calls.getAndIncrement() == 0) {
                throw new TossApiException("시세 조회 실패: HTTP 401", 401);
            }
            return "recovered";
        };

        String result = client.withAuthRetry(call);

        assertThat(result).isEqualTo("recovered");
        assertThat(calls.get()).isEqualTo(2);
        verify(auth, times(1)).forceRefresh();
    }

    @Test
    void propagates_exception_when_retry_also_returns_401() {
        Supplier<String> call = () -> {
            throw new TossApiException("시세 조회 실패: HTTP 401", 401);
        };

        assertThatThrownBy(() -> client.withAuthRetry(call))
                .isInstanceOf(TossApiException.class)
                .extracting(e -> ((TossApiException) e).getStatus())
                .isEqualTo(401);
        verify(auth, times(1)).forceRefresh(); // 1회 재시도 한도 — 무한루프 아님
    }

    @Test
    void non_401_errors_are_not_retried() {
        Supplier<String> call = () -> {
            throw new TossApiException("시세 조회 실패: HTTP 429", 429);
        };

        assertThatThrownBy(() -> client.withAuthRetry(call))
                .isInstanceOf(TossApiException.class)
                .extracting(e -> ((TossApiException) e).getStatus())
                .isEqualTo(429);
        verify(auth, never()).forceRefresh();
    }
}
