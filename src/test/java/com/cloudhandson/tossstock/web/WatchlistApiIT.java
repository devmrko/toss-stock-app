package com.cloudhandson.tossstock.web;

import com.cloudhandson.tossstock.watchlist.Watchlist;
import com.cloudhandson.tossstock.watchlist.WatchlistQuote;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT;

/**
 * 워치리스트 API end-to-end (실 Oracle + 실 토스). ORACLE_PASSWORD/TOSS_CLIENT_KEY 있을 때만.
 * 설계: docs/design/414-watchlist-page/README.md §10
 */
@SpringBootTest(webEnvironment = RANDOM_PORT)
@ActiveProfiles("oracle")
@EnabledIfEnvironmentVariable(named = "ORACLE_PASSWORD", matches = ".+")
@EnabledIfEnvironmentVariable(named = "TOSS_CLIENT_KEY", matches = ".+")
class WatchlistApiIT {

    @Autowired
    TestRestTemplate http;

    @Test
    void add_then_quotes_sorted_by_volume_then_delete() {
        // add (이미 있으면 409 허용 — 멱등)
        ResponseEntity<Watchlist> added = http.postForEntity(
                "/api/watchlist", new WatchlistController.AddRequest("000660", "IT"), Watchlist.class);
        assertThat(added.getStatusCode()).isIn(HttpStatus.CREATED, HttpStatus.CONFLICT);

        // quotes: 거래량 내림차순 + 순위 1..n
        ResponseEntity<List<WatchlistQuote>> quotes = http.exchange(
                "/api/watchlist/quotes", org.springframework.http.HttpMethod.GET, null,
                new ParameterizedTypeReference<List<WatchlistQuote>>() {});
        assertThat(quotes.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<WatchlistQuote> rows = quotes.getBody();
        assertThat(rows).isNotNull();

        for (int i = 1; i < rows.size(); i++) {
            Long prev = rows.get(i - 1).volume();
            Long cur = rows.get(i).volume();
            if (prev != null && cur != null) {
                assertThat(prev).isGreaterThanOrEqualTo(cur);   // 거래량 desc
            }
            assertThat(rows.get(i).rank()).isEqualTo(i + 1);    // 순위 연속
        }
        assertThat(rows.size()).isLessThanOrEqualTo(50);

        // cleanup: 이 테스트가 새로 만든 행만 삭제
        if (added.getStatusCode() == HttpStatus.CREATED && added.getBody() != null) {
            http.delete("/api/watchlist/" + added.getBody().getId());
        }
    }
}
