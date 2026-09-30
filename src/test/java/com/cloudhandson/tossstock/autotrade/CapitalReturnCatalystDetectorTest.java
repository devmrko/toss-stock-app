package com.cloudhandson.tossstock.autotrade;

import com.cloudhandson.tossstock.news.StockNews;
import com.cloudhandson.tossstock.news.StockNewsMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * CapitalReturnCatalystDetector 검증 — 디즈니($9B 자사주매입)/Lear($1.5B 자사주 확대) 실제
 * 관찰 사례가 저평가 절대치는 탈락해도 이 촉매로는 통과해야 한다는 취지(2026-09-29).
 */
class CapitalReturnCatalystDetectorTest {

    private static StockNews news(String title, String rationale, String kind, String sentiment) {
        StockNews n = new StockNews();
        n.setTitle(title);
        n.setRationale(rationale);
        n.setKind(kind);
        n.setSentiment(sentiment);
        return n;
    }

    @Test
    void buyback_event_with_strong_sentiment_detected() {
        StockNewsMapper mapper = mock(StockNewsMapper.class);
        when(mapper.active("LEA", 20)).thenReturn(List.of(
                news("Lear boosts share repurchase authorization to $1.5B", "자사주매입 한도 확대", "EVENT", "LEA:S4")));
        CapitalReturnCatalystDetector d = new CapitalReturnCatalystDetector(mapper);
        assertThat(d.hasRecentCatalyst("LEA")).isTrue();
    }

    @Test
    void dividend_increase_detected() {
        StockNewsMapper mapper = mock(StockNewsMapper.class);
        when(mapper.active("DIS", 20)).thenReturn(List.of(
                news("Disney 자사주 9억달러 매입 발표", "주주환원 확대", "EVENT", "DIS:S5")));
        CapitalReturnCatalystDetector d = new CapitalReturnCatalystDetector(mapper);
        assertThat(d.hasRecentCatalyst("DIS")).isTrue();
    }

    @Test
    void speculation_kind_is_ignored_even_with_keyword() {
        StockNewsMapper mapper = mock(StockNewsMapper.class);
        when(mapper.active("AAA", 20)).thenReturn(List.of(
                news("증권가 \"AAA 배당 확대 기대\"", "전망", "SPECULATION", "AAA:S4")));
        CapitalReturnCatalystDetector d = new CapitalReturnCatalystDetector(mapper);
        assertThat(d.hasRecentCatalyst("AAA")).isFalse();
    }

    @Test
    void weak_sentiment_below_s4_ignored() {
        StockNewsMapper mapper = mock(StockNewsMapper.class);
        when(mapper.active("BBB", 20)).thenReturn(List.of(
                news("BBB 배당 관련 공시", "단순 공시", "EVENT", "BBB:S3")));
        CapitalReturnCatalystDetector d = new CapitalReturnCatalystDetector(mapper);
        assertThat(d.hasRecentCatalyst("BBB")).isFalse();
    }

    @Test
    void large_scale_expansion_investment_detected() {
        // 2026-09-30: 검증단계 완화 — 삼성전기(009150) 실사례. 배당/자사주 아닌 CAPEX성 투자도 촉매로 인정.
        StockNewsMapper mapper = mock(StockNewsMapper.class);
        when(mapper.active("009150", 20)).thenReturn(List.of(
                news("삼성전기, 세종에 4.2조 투자…AI 서버용 반도체 기판 증설", "역대급 투자", "EVENT", "009150:S5")));
        CapitalReturnCatalystDetector d = new CapitalReturnCatalystDetector(mapper);
        assertThat(d.hasRecentCatalyst("009150")).isTrue();
    }

    @Test
    void supply_contract_win_detected() {
        StockNewsMapper mapper = mock(StockNewsMapper.class);
        when(mapper.active("EEE", 20)).thenReturn(List.of(
                news("EEE, AI 서버용 부품 2850억원 공급계약 체결", "대형 수주", "EVENT", "EEE:S5")));
        CapitalReturnCatalystDetector d = new CapitalReturnCatalystDetector(mapper);
        assertThat(d.hasRecentCatalyst("EEE")).isTrue();
    }

    @Test
    void no_keyword_match_returns_false() {
        StockNewsMapper mapper = mock(StockNewsMapper.class);
        when(mapper.active("CCC", 20)).thenReturn(List.of(
                news("CCC 신제품 출시", "신제품 흥행", "EVENT", "CCC:S5")));
        CapitalReturnCatalystDetector d = new CapitalReturnCatalystDetector(mapper);
        assertThat(d.hasRecentCatalyst("CCC")).isFalse();
    }

    @Test
    void empty_news_list_returns_false() {
        StockNewsMapper mapper = mock(StockNewsMapper.class);
        when(mapper.active("DDD", 20)).thenReturn(List.of());
        CapitalReturnCatalystDetector d = new CapitalReturnCatalystDetector(mapper);
        assertThat(d.hasRecentCatalyst("DDD")).isFalse();
    }
}
