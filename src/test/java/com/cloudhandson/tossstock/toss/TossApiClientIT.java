package com.cloudhandson.tossstock.toss;

import com.cloudhandson.tossstock.toss.dto.TossAccount;
import com.cloudhandson.tossstock.toss.dto.TossPrice;
import com.cloudhandson.tossstock.toss.dto.TossStock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 실제 토스 Open API 통합 테스트.
 * TOSS_CLIENT_KEY 환경변수가 있을 때만 실행 (CI/키 없는 환경에서는 자동 skip).
 * 실행: set -a; . ./.env; set +a; mvn test -Dtest=TossApiClientIT
 */
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "TOSS_CLIENT_KEY", matches = ".+")
class TossApiClientIT {

    @Autowired
    TossAuthClient authClient;

    @Autowired
    TossApiClient apiClient;

    @Test
    void issues_access_token() {
        String token = authClient.getAccessToken();
        assertThat(token).isNotBlank();
    }

    @Test
    void fetches_price_for_samsung() {
        List<TossPrice> prices = apiClient.getPrices(List.of("005930"));
        assertThat(prices).isNotEmpty();
        assertThat(prices.get(0).symbol()).isEqualTo("005930");
        assertThat(prices.get(0).lastPrice()).isNotBlank();
    }

    @Test
    void fetches_stock_info_for_samsung() {
        List<TossStock> stocks = apiClient.getStocks(List.of("005930"));
        assertThat(stocks).isNotEmpty();
        assertThat(stocks.get(0).name()).isEqualTo("삼성전자");
    }

    @Test
    void lists_accounts() {
        List<TossAccount> accounts = apiClient.getAccounts();
        assertThat(accounts).isNotEmpty();
        assertThat(accounts.get(0).accountNo()).isNotBlank();
    }
}
