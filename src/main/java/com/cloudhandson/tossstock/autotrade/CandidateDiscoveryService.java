package com.cloudhandson.tossstock.autotrade;

import com.cloudhandson.tossstock.market.UniverseMapper;
import com.cloudhandson.tossstock.news.NewsSignals;
import com.cloudhandson.tossstock.news.StockNews;
import com.cloudhandson.tossstock.news.StockNewsMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;

/**
 * 후보(auto_trade_candidate) 자동 등록/정리 — 사용자 지적(2026-09-29): 지금까지 후보를
 * 사람이 수동으로 SQL로 넣어왔는데, 이건 "유동적으로 계속 모니터링"돼야 한다는 취지에 안 맞음.
 * 최근 뉴스에서 EVENT+호재(S4/S5) 개별종목을 자동으로 후보에 넣고, 호재가 식으면 자동으로 뺀다.
 * 종목당 저평가/펀더멘털/인기 판정은 여전히 AutoTradeScheduler가 매수 시점에 함 — 여기선
 * "볼 가치가 있는 후보 풀"만 관리(느슨한 필터, 나머지는 기존 게이트가 거른다).
 * 설계: docs/design/808-auto-trade-engine/README.md §7(2026-09-29 추가)
 */
@Service
public class CandidateDiscoveryService {

    private static final Logger log = LoggerFactory.getLogger(CandidateDiscoveryService.class);
    private static final int LOOKBACK_HOURS = 24;

    private final StockNewsMapper newsMapper;
    private final AutoTradeCandidateMapper candidateMapper;
    private final UniverseMapper universeMapper;
    private final NewsFadeDetector newsFadeDetector;

    public CandidateDiscoveryService(StockNewsMapper newsMapper, AutoTradeCandidateMapper candidateMapper,
                                      UniverseMapper universeMapper, NewsFadeDetector newsFadeDetector) {
        this.newsMapper = newsMapper;
        this.candidateMapper = candidateMapper;
        this.universeMapper = universeMapper;
        this.newsFadeDetector = newsFadeDetector;
    }

    @Scheduled(cron = "${auto-trade.discovery-cron:0 */15 * * * *}", zone = "Asia/Seoul")
    public void refresh() {
        addNewCandidates();
        removeFadedCandidates();
    }

    private void addNewCandidates() {
        Set<String> seen = new HashSet<>();
        for (StockNews n : newsMapper.findRecentEvents(LocalDateTime.now().minusHours(LOOKBACK_HOURS))) {
            for (String target : n.getTargets().split(",")) {
                String symbol = target.trim();
                if (symbol.isEmpty() || !seen.add(symbol)) {
                    continue;
                }
                String level = NewsSignals.levelOf(n.getSentiment(), symbol);
                if (level == null || Integer.parseInt(level.substring(1)) < 4) {
                    continue;
                }
                String market = marketOf(symbol);
                if (market == null) {
                    continue; // 섹터명/MARKET 등 종목 아닌 타겟
                }
                if (candidateMapper.existsActive(symbol)) {
                    continue;
                }
                AutoTradeCandidate c = new AutoTradeCandidate();
                c.setSymbol(symbol);
                c.setMarket(market);
                c.setValuationNote("자동발견(" + LocalDateTime.now() + "): " + n.getTitle());
                candidateMapper.insert(c);
                log.info("후보 자동등록: {} ({}) - {}", symbol, market, n.getTitle());
            }
        }
    }

    private void removeFadedCandidates() {
        for (AutoTradeCandidate c : candidateMapper.findActive()) {
            if (newsFadeDetector.hasNewsFaded(c.getSymbol())) {
                candidateMapper.deactivate(c.getSymbol());
                log.info("후보 자동해제(호재 소멸): {}", c.getSymbol());
            }
        }
    }

    /** KR 6자리 숫자코드 또는 us_universe 실존 티커. 섹터명/MARKET이면 null. */
    private String marketOf(String symbol) {
        if (symbol.matches("\\d{6}")) {
            return "KR";
        }
        if (symbol.matches("[A-Z]{1,5}") && universeMapper.existsUsSymbol(symbol)) {
            return "US";
        }
        return null;
    }
}
