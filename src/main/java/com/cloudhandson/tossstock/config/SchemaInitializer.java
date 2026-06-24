package com.cloudhandson.tossstock.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 기동 시 Oracle 스키마를 멱등 생성(테이블이 없으면 만든다). clone 후 별도 DDL 실행 없이 구동되게 한다.
 * - db/*.sql 은 PL/SQL 익명블록('/' 구분, ORA-00955/-01430 자체 무시)이라 반복 실행 안전.
 * - 운영(default) 프로파일에서만 동작(테스트 H2 제외). app.schema.auto-init=false 로 끌 수 있음.
 */
@Component
@Profile("default")
@Order(0)
@ConditionalOnProperty(name = "app.schema.auto-init", havingValue = "true", matchIfMissing = true)
public class SchemaInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SchemaInitializer.class);

    /** 순서 중요: universe(top50.sql) → 이를 ALTER 하는 daily_ohlcv.sql, 이후 독립 테이블들. */
    private static final List<String> SCRIPTS = List.of(
            "db/top50.sql",        // universe, volume_rank
            "db/daily_ohlcv.sql",  // daily_ohlcv + universe ALTER(sector)
            "db/watchlist.sql",    // watchlist
            "db/holding.sql",      // holding (+ side)
            "db/stock_news.sql",   // stock_news
            "db/us_universe.sql",  // us_universe
            "db/broker.sql"        // broker_ref (#442)
    );

    private final JdbcTemplate jdbc;

    public SchemaInitializer(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void run(ApplicationArguments args) {
        int ok = 0;
        for (String path : SCRIPTS) {
            ok += runScript(path);
        }
        log.info("스키마 초기화 완료(멱등): {}개 DDL 블록 적용", ok);
    }

    private int runScript(String path) {
        String sql;
        try (InputStream in = new ClassPathResource(path).getInputStream()) {
            sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.warn("DDL 스크립트 읽기 실패 {}: {}", path, e.getMessage());
            return 0;
        }
        int applied = 0;
        for (String block : sql.split("(?m)^/\\s*$")) {
            String stmt = block.lines()
                    .filter(l -> !l.strip().startsWith("--"))
                    .collect(Collectors.joining("\n")).strip();
            if (stmt.isEmpty()) {
                continue;
            }
            try {
                jdbc.execute(stmt);
                applied++;
            } catch (DataAccessException e) {
                // 익명블록이 ORA-955/-1430 을 자체 무시하므로 여기 도달은 예상 밖 — 경고만.
                log.warn("DDL 블록 경고({}): {}", path, e.getMostSpecificCause().getMessage());
            }
        }
        return applied;
    }
}
