package com.cloudhandson.tossstock.autotrade;

import com.cloudhandson.tossstock.market.DailyOhlcvMapper;
import com.cloudhandson.tossstock.news.NewsSignals;
import com.cloudhandson.tossstock.news.StockNews;
import com.cloudhandson.tossstock.news.StockNewsMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.MathContext;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 후보(auto_trade_candidate) 자동 등록/정리.
 *
 * <p><b>#887(2026-10-09) 전환.</b> 2026-09-29 부터 이 클래스는 "최근 뉴스에서 EVENT+호재(S4/S5)
 * 개별종목을 후보에 넣고 호재가 식으면 뺀다"였다. 그 전제가 음수 기대값으로 측정됐다 —
 * KOSPI 가 +183.6% 오른 2025-01~2026-10 구간에 뉴스일 진입의 실현손익은 -0.11%(수수료 전,
 * N=2,149)였고, 같은 종목의 뉴스 없는 날은 +7.71%, 지수 보유는 +5.70% 였다.
 * 급등률을 통제해도 전 구간에서 4.6~8.6%p 열등했고 10거래일 대기로도 회복되지 않았다.
 * 측정: docs/analysis/2026-10-09-news-trigger-negative-expectancy.md
 *
 * <p>그래서 <b>뉴스는 배제 필터로만</b> 쓴다({@link NewsRiskExclusion}) — 리스크 이벤트가 난
 * 종목은 사지 않는다. 후보 발굴은 원칙 §3 의 자동화 가능 항목을 로컬 일봉으로 보는
 * {@link UniverseScreener} 가 맡는다. 보유 중 논거 무효 매도는 {@link RiskEventDetector}(#872)
 * 가 그대로 담당한다.
 *
 * <p>종목당 저평가/펀더멘털/인기 판정은 여전히 {@link AutoTradeScheduler} 가 매수 시점에
 * 한다 — 여기선 "볼 가치가 있는 후보 풀"만 관리한다. 그 분리 덕에 발굴은 외부 API 를
 * 한 번도 호출하지 않는다(설계 fn-refresh.md §8).
 *
 * 설계: docs/design/887-news-exclusion-filter/README.md
 */
@Service
public class CandidateDiscoveryService {

    private static final Logger log = LoggerFactory.getLogger(CandidateDiscoveryService.class);
    private static final int LOOKBACK_HOURS = 24;

    /** KR 지수 대용(KODEX 200). 상대강세의 기준선. */
    private static final String KR_INDEX_SYMBOL = "069500";

    /**
     * 스냅샷 조회 하한(일). 20거래일 수익률 + 평균거래대금 + 최근 저점에 21바가 필요하고,
     * 휴장일을 포함해 안전하게 덮으려면 90일이면 충분하다(실측 21개월 구간 평균 월 20.4바).
     */
    private static final int SNAPSHOT_DAYS = 90;

    private final StockNewsMapper newsMapper;
    private final AutoTradeCandidateMapper candidateMapper;
    private final NewsRiskExclusion newsRiskExclusion;
    private final AutoTradeProperties props;
    private final DailyOhlcvMapper dailyMapper;
    private final AutoTradePositionMapper positionMapper;

    public CandidateDiscoveryService(StockNewsMapper newsMapper, AutoTradeCandidateMapper candidateMapper,
                                      NewsRiskExclusion newsRiskExclusion, AutoTradeProperties props,
                                      DailyOhlcvMapper dailyMapper, AutoTradePositionMapper positionMapper) {
        this.newsMapper = newsMapper;
        this.candidateMapper = candidateMapper;
        this.newsRiskExclusion = newsRiskExclusion;
        this.props = props;
        this.dailyMapper = dailyMapper;
        this.positionMapper = positionMapper;
    }

    /**
     * 스크리너 결과와 활성 후보 목록을 동기화한다.
     *
     * <p>조회 실패는 <b>사이클 포기</b>로 처리한다(fail-closed). 특히 스냅샷이 0건일 때
     * 기존 후보를 비활성화하지 않는 것이 중요하다 — 조회 장애를 "전 종목 이탈"로 오인해
     * 후보를 전멸시키면 그 사이 매수가 멈춘다.
     *
     * 설계: docs/design/887-news-exclusion-filter/fn-refresh.md §5
     */
    @Scheduled(cron = "${auto-trade.discovery-cron:0 */15 * * * *}", zone = "Asia/Seoul")
    public void refresh() {
        UniverseScreener.ScreenResult result;
        List<AutoTradeCandidate> active;
        try {
            List<ScreeningRow> snapshot = dailyMapper.screeningSnapshot(
                    LocalDate.now().minusDays(SNAPSHOT_DAYS), props.extensionLookbackDays());
            if (snapshot.isEmpty()) {
                log.warn("스크리너 스냅샷 0건 — 이번 사이클 포기(기존 후보 유지)");
                return;
            }
            Set<String> excluded = newsRiskExclusion.excludedSymbols(
                    LocalDateTime.now().minusDays(props.riskExclusionDays()));
            result = UniverseScreener.screen(snapshot, excluded, indexReturns(snapshot), screenParams());
            active = candidateMapper.findActive();
        } catch (Exception e) {
            // 배제 목록이나 스냅샷을 모르는 상태로 후보를 등록하면 유상증자 공시가 난 종목을
            // 살 수 있다. 기존 후보는 그대로 두므로 매도 감시(HARD_STOP/TRAIL/RISK_EVENT)는 계속 돈다.
            log.error("스크리너 사이클 포기(조회 실패) — 기존 후보 유지: {}", e.toString());
            return;
        }

        log.info("스크리너(#887) {}: {}", props.screenerMarkets(),
                result.funnel().describe(props.maxExtensionPct(), props.screenTopN()));

        Set<String> selectedSymbols = result.selected().stream()
                .map(ScreenCandidate::symbol).collect(Collectors.toSet());
        deactivateStale(active, selectedSymbols);
        register(result.selected());
    }

    /**
     * 비활성화 — 등록을 <b>먼저</b> 하면 "스크리너 통과 + 구 노트 보유" 종목이 등록 직후
     * 비활성화되어 사라진다. 순서가 결과를 바꾸므로 고정한다.
     */
    private void deactivateStale(List<AutoTradeCandidate> active, Set<String> selectedSymbols) {
        for (AutoTradeCandidate c : active) {
            String note = c.getValuationNote();
            String reason;
            if (isLegacyNewsNote(note)) {
                reason = "구 뉴스 발굴분(#887 전환)";
            } else if (isScreenerNote(note) && !selectedSymbols.contains(c.getSymbol())) {
                reason = "스크리너 이탈";
            } else {
                continue;   // 수동 등록분은 건드리지 않는다 — 사람이 넣은 것을 자동 판정으로 뺄 권한이 없다
            }
            try {
                candidateMapper.deactivate(c.getSymbol());
                log.info("후보 해제({}): {} - {}", reason, c.getSymbol(), note);
            } catch (Exception e) {
                log.warn("후보 해제 실패(다음 사이클 재시도): {} - {}", c.getSymbol(), e.toString());
            }
        }
    }

    private void register(List<ScreenCandidate> selected) {
        LocalDate today = LocalDate.now();
        for (ScreenCandidate sc : selected) {
            try {
                if (candidateMapper.existsActive(sc.symbol())) {
                    continue;   // 이미 활성(수동 등록 포함) — 수동이 우선한다
                }
                AutoTradeCandidate c = new AutoTradeCandidate();
                c.setSymbol(sc.symbol());
                c.setMarket(sc.market());
                c.setValuationNote(UniverseScreener.noteOf(today, sc));
                candidateMapper.insert(c);
                log.info("후보 등록: {} ({}) {}", sc.symbol(), sc.market(),
                        UniverseScreener.noteOf(today, sc));
            } catch (Exception e) {
                log.warn("후보 등록 실패(다음 사이클 재시도): {} - {}", sc.symbol(), e.toString());
            }
        }
    }

    /** 2026-09-29~2026-10-09 뉴스 발굴분. 1사이클에 전부 비활성화된다(AC9). */
    static boolean isLegacyNewsNote(String note) {
        return note != null && note.startsWith("자동발견(");
    }

    static boolean isScreenerNote(String note) {
        return note != null && note.startsWith(UniverseScreener.NOTE_PREFIX);
    }

    private UniverseScreener.ScreenParams screenParams() {
        return new UniverseScreener.ScreenParams(marketSet(props.screenerMarkets()),
                props.minAvgTradingValue(), props.minAvgTradingValueUsd(),
                props.maxExtensionPct(), props.screenTopN());
    }

    /** {@code "KR,US"} → {KR, US}. 빈 값이면 빈 집합 — 후보 0건이 되는 킬스위치다. */
    static Set<String> marketSet(String csv) {
        if (csv == null || csv.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(csv.split(",")).map(String::trim).filter(s -> !s.isEmpty())
                .map(String::toUpperCase).collect(Collectors.toSet());
    }

    /**
     * 시장별 지수 20거래일 수익률(%). KR 은 스냅샷 안의 {@value #KR_INDEX_SYMBOL} 로 계산한다 —
     * 외부 호출이 없다. US 는 Toss 캔들 API 가 ETF 를 지원하지 않아 야후 경로가 필요하고,
     * US 는 #885(일봉 거래량 결함)·#886(환전 미적용) 해결 전까지 범위 외이므로 넣지 않는다.
     */
    static Map<String, Double> indexReturns(List<ScreeningRow> snapshot) {
        Double krReturn = index20dReturnOf(snapshot, KR_INDEX_SYMBOL);
        return krReturn == null ? Map.of() : Map.of("KR", krReturn);
    }

    /** 스냅샷에서 한 종목의 20거래일 수익률(%). 바가 모자라면 null → 그 시장 전체 탈락. */
    static Double index20dReturnOf(List<ScreeningRow> snapshot, String symbol) {
        for (ScreeningRow r : snapshot) {
            if (!symbol.equals(r.symbol())) {
                continue;
            }
            if (r.latestClose() == null || r.closeBefore20() == null || r.closeBefore20().signum() <= 0) {
                return null;
            }
            return r.latestClose().subtract(r.closeBefore20())
                    .divide(r.closeBefore20(), MathContext.DECIMAL64).doubleValue() * 100;
        }
        return null;
    }

    /**
     * 오늘 하드/트레일스탑으로 손절된 포지션과 "같은 테마"의 재진입인지(#840, 2026-10-07).
     * 066570 실사례: "AI 데이터센터 냉각 계약"(테마: 전자부품)으로 사서 손절된 뒤,
     * "3분기 영업이익 4조 돌파"(테마: 전자부품)로 7초 만에 재매수 — 헤드라인은 다르지만 같은
     * 테마 태그를 공유하는 같은 스토리의 연장. #839(시간 쿨다운)로는 못 잡는 패턴.
     * 손절이 오늘이 아니거나, 양쪽 테마 태그를 못 찾으면(데이터 부족) false(fail-open).
     *
     * <p>#887 이후에도 유지한다 — 뉴스를 진입 근거로 쓰지 않더라도, 손절 직후 같은 테마로
     * 되돌아가는 것은 막아야 한다. 뉴스는 여기서 "무엇의 연장인가"를 알려주는 라벨로만 쓰인다.
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
}
