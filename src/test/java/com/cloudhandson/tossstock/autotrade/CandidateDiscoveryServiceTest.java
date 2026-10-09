package com.cloudhandson.tossstock.autotrade;

import com.cloudhandson.tossstock.market.DailyOhlcvMapper;
import com.cloudhandson.tossstock.news.StockNews;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * #887 스크리너 기반 후보 동기화. 설계: docs/design/887-news-exclusion-filter/fn-refresh.md §10
 *
 * <p>#887 이전 이 파일에는 뉴스 발굴(등급·촉매자격·제목가드·뉴스소멸) 테스트 30여 개가 있었다.
 * 그 경로를 제거했으므로 함께 지웠다 — 대상 코드가 없는 테스트는 회귀를 지키지 못한다.
 * 촉매 자격 판정 자체의 테스트는 {@code CatalystQualifierTest}·{@code TitleGuardTest} 에
 * 그대로 남아 있다(설계 §13-2: 클래스는 남기고 진입 경로에서만 뺐다).
 */
class CandidateDiscoveryServiceTest {

    private static final String KR_INDEX = "069500";

    private com.cloudhandson.tossstock.news.StockNewsMapper newsMapper;
    private AutoTradeCandidateMapper candidateMapper;
    private NewsRiskExclusion newsRiskExclusion;
    private DailyOhlcvMapper dailyMapper;
    private AutoTradePositionMapper positionMapper;
    private CandidateDiscoveryService service;

    @BeforeEach
    void setUp() {
        newsMapper = mock(com.cloudhandson.tossstock.news.StockNewsMapper.class);
        candidateMapper = mock(AutoTradeCandidateMapper.class);
        newsRiskExclusion = mock(NewsRiskExclusion.class);
        dailyMapper = mock(DailyOhlcvMapper.class);
        positionMapper = mock(AutoTradePositionMapper.class);
        AutoTradeProperties props = new AutoTradeProperties(true, BigDecimal.valueOf(3_000_000), 5,
                BigDecimal.valueOf(600_000), 15.0, 10.0, 10.0, "", "0 * * * * *", 20, 1.0, 1.5, 30.0, 3.0, 200.0,
                BigDecimal.valueOf(300_000_000), BigDecimal.valueOf(200_000), 20, 2, 30, 60, 30,
                6.0, 5, 15.0, 40, "KR", 7, 3.0, 300.0, 15.0, 10.0, "0 30 8 * * MON",
                new AutoTradeProperties.Gate(20, 100, 2.5, 20, 0.2));
        service = new CandidateDiscoveryService(newsMapper, candidateMapper, newsRiskExclusion,
                props, dailyMapper, positionMapper);
        when(candidateMapper.findActive()).thenReturn(List.of());
        when(newsRiskExclusion.excludedSymbols(any())).thenReturn(Set.of());
    }

    // ---- 스냅샷 입력 헬퍼 ----

    /** 지수(069500) + 주어진 종목들. 지수는 20일 +0% 로 둬서 상대강세 판정의 기준을 0 으로 만든다. */
    private void givenSnapshot(ScreeningRow... rows) {
        java.util.List<ScreeningRow> all = new java.util.ArrayList<>();
        all.add(row(KR_INDEX, "1000", "1000", "999999999999", "1000"));
        all.addAll(List.of(rows));
        when(dailyMapper.screeningSnapshot(any(LocalDate.class), anyInt())).thenReturn(all);
    }

    /** 통과 기준선: 유동성 충분(10억), 상대강세 +5%, 급등률 +1%. */
    private static ScreeningRow passing(String symbol) {
        return row(symbol, "101", "100", "1000000000", "100");
    }

    private static ScreeningRow row(String symbol, String latest, String before20,
                                     String turnover, String lowRecent) {
        return new ScreeningRow(symbol, "KR", new BigDecimal(latest), new BigDecimal(before20),
                new BigDecimal(turnover), new BigDecimal(lowRecent), 60);
    }

    private static AutoTradeCandidate active(String symbol, String note) {
        AutoTradeCandidate c = new AutoTradeCandidate();
        c.setSymbol(symbol);
        c.setMarket("KR");
        c.setValuationNote(note);
        return c;
    }

    // ---- AC1: 뉴스만으로는 등록되지 않는다 ----

    @Test
    void 뉴스는_후보_등록에_관여하지_않는다() {
        // AC1 — 스냅샷이 통과 종목을 주지 않으면, 뉴스가 아무리 있어도 등록은 0건이다.
        // 뉴스 조회 자체가 발굴 경로에 없음을 고정한다(테마 판정은 별 경로).
        givenSnapshot();   // 지수만 — 통과 종목 없음

        service.refresh();

        verify(candidateMapper, never()).insert(any());
        verify(newsMapper, never()).findRecentEvents(any());
    }

    @Test
    void 스크리너_통과분이_등록된다() {
        givenSnapshot(passing("005930"));

        service.refresh();

        org.mockito.ArgumentCaptor<AutoTradeCandidate> cap =
                org.mockito.ArgumentCaptor.forClass(AutoTradeCandidate.class);
        verify(candidateMapper).insert(cap.capture());
        assertThat(cap.getValue().getSymbol()).isEqualTo("005930");
        assertThat(cap.getValue().getMarket()).isEqualTo("KR");
        assertThat(cap.getValue().getValuationNote()).startsWith(UniverseScreener.NOTE_PREFIX);
    }

    // ---- AC2: 리스크 이벤트 배제 ----

    @Test
    void 리스크_기사가_있는_종목은_등록되지_않는다() {
        givenSnapshot(passing("005930"));
        when(newsRiskExclusion.excludedSymbols(any())).thenReturn(Set.of("005930"));

        service.refresh();

        verify(candidateMapper, never()).insert(any());
    }

    // ---- AC4: 등록 상한 ----

    @Test
    void 등록은_screen_top_n_이하로_묶인다() {
        ScreeningRow[] rows = new ScreeningRow[50];
        for (int i = 0; i < 50; i++) {
            // 거래대금을 달리 줘서 정렬이 결정론적이게 한다.
            rows[i] = row(String.format("%06d", i), "101", "100", String.valueOf(1_000_000_000L + i), "100");
        }
        givenSnapshot(rows);

        service.refresh();

        verify(candidateMapper, org.mockito.Mockito.times(40)).insert(any());
    }

    // ---- AC9 / AC10: 비활성화 대상 ----

    @Test
    void 구_뉴스발굴_후보는_전부_해제된다() {
        // AC9 — 2026-09-29~10-09 사이 "자동발견(" 노트로 들어온 후보. 1사이클에 전부 비활성화.
        givenSnapshot();
        when(candidateMapper.findActive())
                .thenReturn(List.of(active("259630", "자동발견(2026-10-08T21:58, 촉매점수 1/4): 어떤 제목")));

        service.refresh();

        verify(candidateMapper).deactivate("259630");
    }

    @Test
    void 스크리너_이탈분은_해제된다() {
        givenSnapshot(passing("005930"));
        when(candidateMapper.findActive())
                .thenReturn(List.of(active("000660", UniverseScreener.NOTE_PREFIX + "2026-10-08, ...)")));

        service.refresh();

        verify(candidateMapper).deactivate("000660");
    }

    @Test
    void 스크리너_유지분은_해제되지_않는다() {
        givenSnapshot(passing("005930"));
        when(candidateMapper.findActive())
                .thenReturn(List.of(active("005930", UniverseScreener.NOTE_PREFIX + "2026-10-08, ...)")));

        service.refresh();

        verify(candidateMapper, never()).deactivate(anyString());
    }

    @Test
    void 수동_등록_후보는_해제되지_않는다() {
        // AC10 — 사람이 넣은 것을 자동 판정으로 뺄 권한이 없다.
        givenSnapshot();
        when(candidateMapper.findActive())
                .thenReturn(List.of(active("009150", "수동 등록: 장기 관찰")));

        service.refresh();

        verify(candidateMapper, never()).deactivate(anyString());
    }

    @Test
    void 수동_등록분은_스크리너가_통과시켜도_중복_등록되지_않는다() {
        givenSnapshot(passing("005930"));
        when(candidateMapper.existsActive("005930")).thenReturn(true);

        service.refresh();

        verify(candidateMapper, never()).insert(any());
    }

    // ---- 실패 모드: 조회 장애를 이탈로 오인하지 않는다 ----

    @Test
    void 스냅샷이_0건이면_기존_후보를_해제하지_않는다() {
        when(dailyMapper.screeningSnapshot(any(LocalDate.class), anyInt())).thenReturn(List.of());
        when(candidateMapper.findActive())
                .thenReturn(List.of(active("259630", "자동발견(t): 제목")));

        service.refresh();

        verify(candidateMapper, never()).deactivate(anyString());
        verify(candidateMapper, never()).insert(any());
    }

    @Test
    void 배제목록_조회가_실패하면_사이클을_포기한다() {
        // fail-closed — 배제 목록을 모르는 채로 등록하면 유상증자 공시 종목을 살 수 있다.
        givenSnapshot(passing("005930"));
        doThrow(new RuntimeException("DB 장애")).when(newsRiskExclusion).excludedSymbols(any());

        service.refresh();

        verify(candidateMapper, never()).insert(any());
        verify(candidateMapper, never()).deactivate(anyString());
    }

    @Test
    void 지수_바가_없으면_등록이_0건이다() {
        // 상대강세 판정 불가 → 그 시장 전체 탈락(fail-closed). 스냅샷 장애와 달리
        // 비활성화는 정상 진행된다("판정 불가"와 "조회 장애"를 구분한다).
        when(dailyMapper.screeningSnapshot(any(LocalDate.class), anyInt()))
                .thenReturn(List.of(passing("005930")));   // 지수(069500) 없음

        service.refresh();

        verify(candidateMapper, never()).insert(any());
    }

    @Test
    void 개별_등록_실패는_다음_종목을_막지_않는다() {
        givenSnapshot(row("005930", "101", "100", "2000000000", "100"),
                row("000660", "101", "100", "1000000000", "100"));
        doThrow(new RuntimeException("ORA-00001")).when(candidateMapper)
                .insert(org.mockito.ArgumentMatchers.argThat(c -> "005930".equals(c.getSymbol())));

        service.refresh();

        verify(candidateMapper).insert(
                org.mockito.ArgumentMatchers.argThat(c -> "000660".equals(c.getSymbol())));
    }

    // ---- 순수 헬퍼 ----

    @Test
    void 노트_접두사로_자동_수동을_구분한다() {
        assertThat(CandidateDiscoveryService.isLegacyNewsNote("자동발견(t): 제목")).isTrue();
        assertThat(CandidateDiscoveryService.isLegacyNewsNote("스크리너(2026-10-09, ...)")).isFalse();
        assertThat(CandidateDiscoveryService.isLegacyNewsNote(null)).isFalse();
        assertThat(CandidateDiscoveryService.isScreenerNote("스크리너(2026-10-09, ...)")).isTrue();
        assertThat(CandidateDiscoveryService.isScreenerNote("수동 등록: 장기 관찰")).isFalse();
        assertThat(CandidateDiscoveryService.isScreenerNote(null)).isFalse();
    }

    @Test
    void 시장_CSV를_집합으로_바꾼다() {
        assertThat(CandidateDiscoveryService.marketSet("KR")).containsExactly("KR");
        assertThat(CandidateDiscoveryService.marketSet(" kr , us ")).containsExactlyInAnyOrder("KR", "US");
        assertThat(CandidateDiscoveryService.marketSet("")).isEmpty();     // 킬스위치
        assertThat(CandidateDiscoveryService.marketSet(null)).isEmpty();
    }

    @Test
    void 스냅샷에서_지수_수익률을_계산한다() {
        ScreeningRow idx = row(KR_INDEX, "105", "100", "1", "100");
        assertThat(CandidateDiscoveryService.index20dReturnOf(List.of(idx), KR_INDEX))
                .isCloseTo(5.0, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(CandidateDiscoveryService.index20dReturnOf(List.of(idx), "000000")).isNull();
        ScreeningRow noBase = new ScreeningRow(KR_INDEX, "KR", new BigDecimal("105"), null,
                BigDecimal.ONE, new BigDecimal("100"), 60);
        assertThat(CandidateDiscoveryService.index20dReturnOf(List.of(noBase), KR_INDEX)).isNull();
    }

    // ---- #840 동일테마 재진입 차단 (#887 이후에도 유지) ----

    @Test
    void same_theme_as_todays_stop_exit_is_detected() {
        // #840 — 066570 실사례 재현: "전자부품" 테마로 샀다가 오늘 손절, 같은 "전자부품" 테마의
        // 다른 헤드라인으로 재진입 시도 → 같은 스토리로 판정.
        AutoTradePosition stopped = stoppedPosition("066570", LocalDateTime.now().minusHours(20),
                LocalDateTime.now().minusMinutes(10));
        when(positionMapper.findLastStopExited("066570")).thenReturn(stopped);
        when(newsMapper.forSymbolBetween(eq("066570"), any(), any()))
                .thenReturn(List.of(newsWith("066570:S5,전자부품:S4")));
        when(newsMapper.active("066570", 5)).thenReturn(List.of(newsWith("066570:S5,전자부품:S4")));

        assertThat(service.isSameThemeAsRecentStopExit("066570", "KR")).isTrue();
    }

    @Test
    void different_theme_after_stop_exit_is_not_blocked() {
        AutoTradePosition stopped = stoppedPosition("066570", LocalDateTime.now().minusHours(20),
                LocalDateTime.now().minusMinutes(10));
        when(positionMapper.findLastStopExited("066570")).thenReturn(stopped);
        when(newsMapper.forSymbolBetween(eq("066570"), any(), any()))
                .thenReturn(List.of(newsWith("066570:S5,전자부품:S4")));
        when(newsMapper.active("066570", 5)).thenReturn(List.of(newsWith("066570:S5,자동차:S4")));

        assertThat(service.isSameThemeAsRecentStopExit("066570", "KR")).isFalse();
    }

    @Test
    void stop_exit_from_a_prior_day_is_not_compared() {
        AutoTradePosition stopped = stoppedPosition("066570", LocalDateTime.now().minusDays(1).minusHours(20),
                LocalDateTime.now().minusDays(1));
        when(positionMapper.findLastStopExited("066570")).thenReturn(stopped);

        assertThat(service.isSameThemeAsRecentStopExit("066570", "KR")).isFalse();
        verify(newsMapper, never()).forSymbolBetween(any(), any(), any());
    }

    @Test
    void no_theme_tags_found_for_stopped_position_is_fail_open() {
        AutoTradePosition stopped = stoppedPosition("066570", LocalDateTime.now().minusHours(20),
                LocalDateTime.now().minusMinutes(10));
        when(positionMapper.findLastStopExited("066570")).thenReturn(stopped);
        when(newsMapper.forSymbolBetween(eq("066570"), any(), any())).thenReturn(List.of());

        assertThat(service.isSameThemeAsRecentStopExit("066570", "KR")).isFalse();
    }

    private static AutoTradePosition stoppedPosition(String symbol, LocalDateTime entryAt, LocalDateTime exitAt) {
        AutoTradePosition p = new AutoTradePosition();
        p.setSymbol(symbol);
        p.setEntryAt(entryAt);
        p.setExitAt(exitAt);
        p.setExitReason("TRAIL_STOP");
        return p;
    }

    private static StockNews newsWith(String sentiment) {
        StockNews n = new StockNews();
        n.setSentiment(sentiment);
        return n;
    }
}
