package com.cloudhandson.tossstock;

import com.cloudhandson.tossstock.watchlist.WatchlistMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/** 컨텍스트 로드 + HikariCP/MyBatis 스모크 (외부 API 불필요). */
@SpringBootTest
class TossStockAppApplicationTests {

    @Autowired
    WatchlistMapper watchlistMapper;

    @Test
    void context_loads_and_mybatis_reads_seed() {
        // schema.sql 시드(삼성전자) 1건 이상 조회되면 HikariCP+MyBatis 정상.
        assertThat(watchlistMapper.findAll()).isNotEmpty();
    }
}
