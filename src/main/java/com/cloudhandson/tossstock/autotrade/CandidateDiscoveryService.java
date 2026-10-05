package com.cloudhandson.tossstock.autotrade;

import com.cloudhandson.tossstock.market.DailyCollector;
import com.cloudhandson.tossstock.market.DailyOhlcv;
import com.cloudhandson.tossstock.market.DailyOhlcvMapper;
import com.cloudhandson.tossstock.market.UniverseMapper;
import com.cloudhandson.tossstock.news.NewsSignals;
import com.cloudhandson.tossstock.news.StockNews;
import com.cloudhandson.tossstock.news.StockNewsMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 후보(auto_trade_candidate) 자동 등록/정리 — 사용자 지적(2026-09-29): 지금까지 후보를
 * 사람이 수동으로 SQL로 넣어왔는데, 이건 "유동적으로 계속 모니터링"돼야 한다는 취지에 안 맞음.
 * 최근 뉴스에서 EVENT+호재(S4/S5) 개별종목을 자동으로 후보에 넣고, 호재가 식으면 자동으로 뺀다.
 * 종목당 저평가/펀더멘털/인기 판정은 여전히 AutoTradeScheduler가 매수 시점에 함 — 여기선
 * "볼 가치가 있는 후보 풀"만 관리(느슨한 필터, 나머지는 기존 게이트가 거른다).
 * 설계: docs/design/808-auto-trade-engine/README.md §7(2026-09-29 추가)
 * 2026-09-30: 미국 신규 후보는 일봉(daily_ohlcv) 백필 호출 추가 — KR은 DailyCollector가
 * 전 종목(3700+) 매일 정기 스캔하지만 US는 그 정기 스캔 대상(universe 테이블)에 없는 티커가
 * 대부분이라, 백필 없이는 PopularityChecker/RelativeStrengthChecker가 데이터 부족으로 영원히
 * false — 아무리 강한 호재(예: MSFT "28년 만의 최대 분기 상승")라도 매수 후보에서 구조적으로
 * 탈락하고 있었음(실측 확인, 2026-09-30). watchlist/holding 등록 시 이미 쓰던 백필 패턴 재사용.
 * 2026-10-05(#828): 호재가 소멸해도 저평가+상대강세면 최대 보유기간까지 후보를 유지한다
 * (뉴스 TTL이 가격·밸류에이션과 무관하게 후보를 떨어뜨리던 문제 — SMCI 실사례).
 * 설계: docs/design/828-news-independent-retention/README.md
 */
@Service
public class CandidateDiscoveryService {

    private static final Logger log = LoggerFactory.getLogger(CandidateDiscoveryService.class);
    private static final int LOOKBACK_HOURS = 24;

    private final StockNewsMapper newsMapper;
    private final AutoTradeCandidateMapper candidateMapper;
    private final UniverseMapper universeMapper;
    private final NewsFadeDetector newsFadeDetector;
    private final DailyCollector dailyCollector;
    private final AutoTradeProperties props;
    private final ValuationClient valuationClient;
    private final DailyOhlcvMapper dailyMapper;

    public CandidateDiscoveryService(StockNewsMapper newsMapper, AutoTradeCandidateMapper candidateMapper,
                                      UniverseMapper universeMapper, NewsFadeDetector newsFadeDetector,
                                      DailyCollector dailyCollector, AutoTradeProperties props,
                                      ValuationClient valuationClient, DailyOhlcvMapper dailyMapper) {
        this.newsMapper = newsMapper;
        this.candidateMapper = candidateMapper;
        this.universeMapper = universeMapper;
        this.newsFadeDetector = newsFadeDetector;
        this.dailyCollector = dailyCollector;
        this.props = props;
        this.valuationClient = valuationClient;
        this.dailyMapper = dailyMapper;
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
                if ("US".equals(market)) {
                    dailyCollector.backfillSymbol(symbol, null); // 비동기 — KR은 정기 전종목 스캔이 이미 커버
                }
            }
        }
    }

    private void removeFadedCandidates() {
        for (AutoTradeCandidate c : candidateMapper.findActive()) {
            if (!newsFadeDetector.hasNewsFaded(c.getSymbol())) {
                continue; // 호재(S4↑) 아직 살아있음 — 기존 그대로 유지
            }
            if (retainDespiteNewsFade(c)) {
                log.info("후보 유지(호재 소멸이나 저평가+상대강세): {}", c.getSymbol());
                continue;
            }
            candidateMapper.deactivate(c.getSymbol());
            log.info("후보 자동해제(호재 소멸): {}", c.getSymbol());
        }
    }

    /**
     * 호재가 소멸한 후보를 그래도 유지할지 — (1) 등록 후 최대보유기간 내 AND (2) 저평가 AND
     * (3) 지수 대비 상대강세. 판정 불가(등록시각·밸류에이션·가격 데이터 없음)는 fail-closed(유지 안 함).
     * AND 조건이라 평가 순서는 결과와 무관 — 비용이 싼 순서(상한→DB→외부API)로 둬서 네이버/야후
     * 호출을 최소화한다(scanCandidates가 1분마다 호출하므로 레이트리밋에 민감).
     * 설계: docs/design/828-news-independent-retention/README.md §8
     */
    boolean retainDespiteNewsFade(AutoTradeCandidate c) {
        if (!withinRetentionWindow(c.getCreatedAt())) {
            return false;
        }
        if (!isRelativelyStrong(c)) {
            return false;
        }
        Valuation valuation = valuationClient.getValuation(c.getSymbol(), c.getMarket());
        return ValuationChecker.isUndervalued(valuation, props.maxPer(), props.maxPbr());
    }

    /** 등록(created_at) 후 candidate-max-retention-days 이내인지. 등록시각 불명이면 false(좀비 후보 방지). */
    private boolean withinRetentionWindow(LocalDateTime createdAt) {
        return createdAt != null
                && createdAt.isAfter(LocalDateTime.now().minusDays(props.candidateMaxRetentionDays()));
    }

    /**
     * 지수(KR 069500 / US SPY) 대비 동일 윈도우 수익률 초과 여부. 데이터 부족이면 false.
     * 2026-10-05(#828 QA): Toss 캔들 API가 US ETF를 지원 안 해 069500처럼 {@code dailyMapper}로
     * SPY를 조회하면 항상 데이터 없음 — US만 {@link ValuationClient#getIndexReturnPct}(야후
     * 차트 API)로 대체. KR(069500)은 기존 경로(DB) 그대로, 실측상 정상 작동.
     */
    private boolean isRelativelyStrong(AutoTradeCandidate c) {
        LocalDate from = LocalDate.now().minusDays(props.relativeStrengthWindowDays() + 10L);
        List<DailyOhlcv> stockWindow = dailyMapper.recentForSymbols(List.of(c.getSymbol()), from);
        Double stockReturn = RelativeStrengthChecker.pctReturn(stockWindow);
        Double indexReturn = "US".equalsIgnoreCase(c.getMarket())
                ? valuationClient.getIndexReturnPct("SPY", props.relativeStrengthWindowDays())
                : RelativeStrengthChecker.pctReturn(dailyMapper.recentForSymbols(List.of("069500"), from));
        return stockReturn != null && indexReturn != null
                && RelativeStrengthChecker.isRelativelyStrong(stockReturn, indexReturn);
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
