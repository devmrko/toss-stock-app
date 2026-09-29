package com.cloudhandson.tossstock.autotrade;

import com.cloudhandson.tossstock.market.UniverseMapper;
import com.cloudhandson.tossstock.news.StockNews;
import com.cloudhandson.tossstock.news.StockNewsMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/** CandidateDiscoveryService 검증 — 후보 자동등록/자동해제(#808 2026-09-29). */
class CandidateDiscoveryServiceTest {

    private StockNewsMapper newsMapper;
    private AutoTradeCandidateMapper candidateMapper;
    private UniverseMapper universeMapper;
    private NewsFadeDetector newsFadeDetector;
    private CandidateDiscoveryService service;

    @BeforeEach
    void setUp() {
        newsMapper = mock(StockNewsMapper.class);
        candidateMapper = mock(AutoTradeCandidateMapper.class);
        universeMapper = mock(UniverseMapper.class);
        newsFadeDetector = mock(NewsFadeDetector.class);
        service = new CandidateDiscoveryService(newsMapper, candidateMapper, universeMapper, newsFadeDetector);
        when(candidateMapper.findActive()).thenReturn(List.of());
    }

    private static StockNews news(String targets, String sentiment, String title) {
        StockNews n = new StockNews();
        n.setTargets(targets);
        n.setSentiment(sentiment);
        n.setTitle(title);
        return n;
    }

    @Test
    void kr_symbol_with_strong_sentiment_is_registered() {
        when(newsMapper.findRecentEvents(any())).thenReturn(List.of(
                news("009150,전자부품", "009150:S5,전자부품:S4", "삼성전기 기판 증설")));
        when(candidateMapper.existsActive("009150")).thenReturn(false);

        service.refresh();

        verify(candidateMapper).insert(argThat(c -> c.getSymbol().equals("009150") && c.getMarket().equals("KR")));
    }

    @Test
    void us_ticker_verified_against_universe_is_registered() {
        when(newsMapper.findRecentEvents(any())).thenReturn(List.of(
                news("NVDA,반도체", "NVDA:S5,반도체:S4", "엔비디아 자사주 매입")));
        when(candidateMapper.existsActive("NVDA")).thenReturn(false);
        when(universeMapper.existsUsSymbol("NVDA")).thenReturn(true);

        service.refresh();

        verify(candidateMapper).insert(argThat(c -> c.getSymbol().equals("NVDA") && c.getMarket().equals("US")));
    }

    @Test
    void sector_only_target_not_registered() {
        when(newsMapper.findRecentEvents(any())).thenReturn(List.of(
                news("반도체", "반도체:S4", "반도체 업황 개선")));

        service.refresh();

        verify(candidateMapper, never()).insert(any());
    }

    @Test
    void weak_sentiment_below_s4_not_registered() {
        when(newsMapper.findRecentEvents(any())).thenReturn(List.of(
                news("009150", "009150:S3", "삼성전기 단순 공시")));

        service.refresh();

        verify(candidateMapper, never()).insert(any());
    }

    @Test
    void already_active_symbol_not_duplicated() {
        when(newsMapper.findRecentEvents(any())).thenReturn(List.of(
                news("009150", "009150:S5", "삼성전기 추가 뉴스")));
        when(candidateMapper.existsActive("009150")).thenReturn(true);

        service.refresh();

        verify(candidateMapper, never()).insert(any());
    }

    @Test
    void unverified_uppercase_token_not_registered() {
        when(newsMapper.findRecentEvents(any())).thenReturn(List.of(
                news("ABCDE", "ABCDE:S5", "알 수 없는 토큰")));
        when(universeMapper.existsUsSymbol("ABCDE")).thenReturn(false);

        service.refresh();

        verify(candidateMapper, never()).insert(any());
    }

    @Test
    void faded_candidate_is_deactivated() {
        when(newsMapper.findRecentEvents(any())).thenReturn(List.of());
        AutoTradeCandidate existing = new AutoTradeCandidate();
        existing.setSymbol("195870");
        when(candidateMapper.findActive()).thenReturn(List.of(existing));
        when(newsFadeDetector.hasNewsFaded("195870")).thenReturn(true);

        service.refresh();

        verify(candidateMapper).deactivate("195870");
    }

    @Test
    void still_hot_candidate_not_deactivated() {
        when(newsMapper.findRecentEvents(any())).thenReturn(List.of());
        AutoTradeCandidate existing = new AutoTradeCandidate();
        existing.setSymbol("009150");
        when(candidateMapper.findActive()).thenReturn(List.of(existing));
        when(newsFadeDetector.hasNewsFaded("009150")).thenReturn(false);

        service.refresh();

        verify(candidateMapper, never()).deactivate(anyString());
    }
}
