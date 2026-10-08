package com.cloudhandson.tossstock.autotrade;

import com.cloudhandson.tossstock.market.DailyCollector;
import com.cloudhandson.tossstock.market.DailyOhlcv;
import com.cloudhandson.tossstock.market.DailyOhlcvMapper;
import com.cloudhandson.tossstock.market.UniverseMapper;
import com.cloudhandson.tossstock.news.NewsFacts;
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
    private final AutoTradePositionMapper positionMapper;

    public CandidateDiscoveryService(StockNewsMapper newsMapper, AutoTradeCandidateMapper candidateMapper,
                                      UniverseMapper universeMapper, NewsFadeDetector newsFadeDetector,
                                      DailyCollector dailyCollector, AutoTradeProperties props,
                                      ValuationClient valuationClient, DailyOhlcvMapper dailyMapper,
                                      AutoTradePositionMapper positionMapper) {
        this.newsMapper = newsMapper;
        this.candidateMapper = candidateMapper;
        this.universeMapper = universeMapper;
        this.newsFadeDetector = newsFadeDetector;
        this.dailyCollector = dailyCollector;
        this.props = props;
        this.valuationClient = valuationClient;
        this.dailyMapper = dailyMapper;
        this.positionMapper = positionMapper;
    }

    @Scheduled(cron = "${auto-trade.discovery-cron:0 */15 * * * *}", zone = "Asia/Seoul")
    public void refresh() {
        requalifyLegacyCandidates();
        addNewCandidates();
        removeFadedCandidates();
    }

    /**
     * #869 구 게이트(통과율 37%)로 등록된 후보를 비활성화한다. #865 의 자격 게이트는 신규
     * 등록만 거르므로, 그 전에 들어온 후보(노브랜드 버거 매장 확대, 발행어음 특판,
     * 'LG엔솔 리튬 공급받기로' 등)가 그대로 매수 대상으로 남아 있었다.
     *
     * <p>신·구 판별은 노트의 "촉매점수" 문자열로 한다 — #865 이후 코드만 기록하므로 현재
     * 유일한 구분자다(노트 포맷을 바꾸면 이 로직도 함께 고칠 것).
     *
     * <p>자가치유형이다 — 자격 있는 뉴스가 여전히 있으면 바로 아래 addNewCandidates 가
     * 새 기준으로 다시 등록한다. 수동 등록 후보는 건드리지 않는다.
     *
     * <p>후보 비활성화는 매도를 유발하지 않는다: 매도 판정은 newsFadeDetector 만 보고
     * 후보 활성여부는 매수 스캔(AutoTradeScheduler 의 candidateMapper.findActive)에서만 쓴다.
     */
    private void requalifyLegacyCandidates() {
        for (AutoTradeCandidate c : candidateMapper.findActive()) {
            String note = c.getValuationNote();
            if (note == null || !note.startsWith("자동발견(") || note.contains("촉매점수")) {
                continue;   // 수동 등록이거나 이미 새 기준으로 등록된 후보
            }
            candidateMapper.deactivate(c.getSymbol());
            log.info("후보 재심사 해제(구 게이트 등록분): {} - {}", c.getSymbol(), note);
        }
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
                // #865 1단 게이트: 등급(S4↑)만으로는 통과율 37%로 사실상 무필터였다.
                // 원칙 §3 기준의 촉매 자격을 사실(facts)로 판정한다 — facts 없으면 매수 금지.
                CatalystQualifier.Verdict v = CatalystQualifier.qualify(
                        NewsFacts.parse(n.getFacts()), n.getTitle());
                if (!v.pass()) {
                    log.debug("촉매 자격 미달({}): {} - {}", v.reason(), symbol, n.getTitle());
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
                // 촉매 점수(0~4, 원칙 §3 '불변×수출' 근접도)를 노트에 남겨 사후 분석 가능하게.
                c.setValuationNote("자동발견(" + LocalDateTime.now() + ", 촉매점수 " + v.score()
                        + "/4): " + n.getTitle());
                candidateMapper.insert(c);
                log.info("후보 자동등록: {} ({}) 촉매점수 {}/4 - {}", symbol, market, v.score(), n.getTitle());
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
        return isCheapAndRelativelyStrong(c.getSymbol(), c.getMarket());
    }

    /**
     * 저평가 AND 상대강세 — 후보 유지뿐 아니라(#828) 보유 중 포지션의 매도 판정(#834 QA 발견,
     * 2026-10-07)에도 재사용한다. 2026-10-07 실사례: #828로 "뉴스 식었지만 저평가+상대강세"라서
     * 산 종목을 바로 다음 틱에 AutoTradeScheduler.processHolding이 "뉴스 식음"을 이유로 팔아버리고,
     * 판 직후 같은 조건으로 다시 사는 매수-매도 무한반복(259630, 44회 왕복, 실현손실 24,950원)이
     * 실거래에서 발생 — 매수 게이트에만 예외를 넣고 매도 게이트에는 대칭적으로 반영하지 않은 버그.
     * (candidate-max-retention-days 같은 "등록 후 경과일" 개념은 이미 보유 중인 포지션에는 적용할
     * 대상이 없으므로 이 메서드엔 포함하지 않음 — 그건 retainDespiteNewsFade 쪽 책임으로 남김.)
     */
    boolean isCheapAndRelativelyStrong(String symbol, String market) {
        if (!isRelativelyStrong(symbol, market)) {
            return false;
        }
        Valuation valuation = valuationClient.getValuation(symbol, market);
        return ValuationChecker.isUndervalued(valuation, props.maxPer(), props.maxPbr());
    }

    /**
     * 오늘 하드/트레일스탑으로 손절된 포지션과 "같은 테마"의 뉴스로 재진입하려는 건지(#840,
     * 2026-10-07). 066570 실사례: "AI 데이터센터 냉각 계약"(테마: 전자부품)으로 사서 손절된 뒤,
     * "3분기 영업이익 4조 돌파"(테마: 전자부품)로 7초 만에 재매수 — 헤드라인은 다르지만 같은
     * 테마 태그를 공유하는 같은 스토리의 연장. #839(시간 쿨다운)로는 못 잡는 패턴.
     * 손절이 오늘이 아니거나, 양쪽 테마 태그를 못 찾으면(데이터 부족) false(fail-open, §3).
     */
    boolean isSameThemeAsRecentStopExit(String symbol, String market) {
        AutoTradePosition lastStopped = positionMapper.findLastStopExited(symbol);
        if (lastStopped == null || lastStopped.getExitAt() == null
                || !lastStopped.getExitAt().toLocalDate().equals(LocalDate.now())) {
            return false; // 오늘 손절된 게 아니면 테마 비교 자체를 안 함(과차단 방지)
        }
        Set<String> pastThemes = themeTagsFor(symbol,
                newsMapper.forSymbolBetween(symbol, lastStopped.getEntryAt().minusHours(LOOKBACK_HOURS),
                        lastStopped.getExitAt()));
        if (pastThemes.isEmpty()) {
            return false; // 그 포지션을 만든 뉴스의 테마를 못 찾음 — fail-open
        }
        Set<String> currentThemes = themeTagsFor(symbol, newsMapper.active(symbol, 5));
        return currentThemes.stream().anyMatch(pastThemes::contains);
    }

    /** sentiment CSV들에서 종목코드/MARKET 키를 뺀 나머지(섹터/테마) 키만 추출. */
    private static Set<String> themeTagsFor(String symbol, List<StockNews> news) {
        List<String> sentiments = news.stream().map(StockNews::getSentiment).toList();
        Set<String> themes = new HashSet<>(NewsSignals.aggregate(sentiments).keySet());
        themes.remove(symbol);
        themes.remove("MARKET");
        return themes;
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
    private boolean isRelativelyStrong(String symbol, String market) {
        LocalDate from = LocalDate.now().minusDays(props.relativeStrengthWindowDays() + 10L);
        List<DailyOhlcv> stockWindow = dailyMapper.recentForSymbols(List.of(symbol), from);
        Double stockReturn = RelativeStrengthChecker.pctReturn(stockWindow);
        Double indexReturn = "US".equalsIgnoreCase(market)
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
