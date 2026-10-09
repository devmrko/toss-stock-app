package com.cloudhandson.tossstock.autotrade;

import com.cloudhandson.tossstock.news.StockNews;
import com.cloudhandson.tossstock.news.StockNewsMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * #887 매수 배제 목록. 설계: docs/design/887-news-exclusion-filter/fn-excludedSymbols.md §10
 */
class NewsRiskExclusionTest {

    private static final LocalDateTime SINCE = LocalDateTime.of(2026, 10, 2, 0, 0);

    private StockNewsMapper newsMapper;
    private NewsRiskExclusion exclusion;

    @BeforeEach
    void setUp() {
        newsMapper = mock(StockNewsMapper.class);
        exclusion = new NewsRiskExclusion(newsMapper);
    }

    private static StockNews news(String targets, String title, String facts) {
        StockNews n = new StockNews();
        n.setTargets(targets);
        n.setTitle(title);
        n.setFacts(facts);
        return n;
    }

    private void given(StockNews... rows) {
        when(newsMapper.riskCandidatesSince(any())).thenReturn(List.of(rows));
    }

    @Test
    void facts의_riskFlag가_있으면_배제된다() {
        given(news("005930", "삼성전자, 신제품 공개", "{\"riskFlag\":\"GOVERNANCE\"}"));
        assertThat(exclusion.excludedSymbols(SINCE)).containsExactly("005930");
    }

    @Test
    void 제목_유상증자만으로도_배제된다() {
        // 매도 트리거(RiskEventDetector.judge)와 같은 공시형 경로.
        given(news("195870", "에피소드컴퍼니, 140억원 제3자배정 유상증자", null));
        assertThat(exclusion.excludedSymbols(SINCE)).containsExactly("195870");
    }

    @Test
    void 매도_트리거에서_빠진_제목_적자도_매수는_배제한다() {
        // 의도된 비대칭(#877 과의 관계) — 매도 오탐은 실현손실이지만 매수 배제 오탐은
        // 기회비용뿐이다. 그래서 매수 배제는 TitleGuard.riskFlagOf 전체를 쓴다.
        given(news("005930", "갤Z 폴드8 흥행에도…DX부문, 2조 적자", "{\"riskFlag\":\"NONE\"}"));
        assertThat(exclusion.excludedSymbols(SINCE)).containsExactly("005930");
        // 같은 기사는 매도 트리거는 되지 않는다(회귀 고정).
        assertThat(RiskEventDetector.judge("갤Z 폴드8 흥행에도…DX부문, 2조 적자",
                "{\"riskFlag\":\"NONE\"}")).isNull();
    }

    @Test
    void 제목_관리종목도_매수는_배제한다() {
        given(news("000020", "'시총 미달' 프롬바이오, 관리종목 지정에 '급락'", null));
        assertThat(exclusion.excludedSymbols(SINCE)).containsExactly("000020");
    }

    @Test
    void 정상_기사만_있으면_빈_집합() {
        given(news("005930", "HD건설기계, 3900억 규모 수주", "{\"riskFlag\":\"NONE\"}"),
                news("000660", "OO사, 3분기 흑자전환 성공", "{\"riskFlag\":\"NONE\"}"));
        assertThat(exclusion.excludedSymbols(SINCE)).isEmpty();
    }

    @Test
    void 타겟이_여러개면_전부_배제된다() {
        // 교차오염 오탐 가능성은 있으나 매수 배제는 보수적으로 간다.
        given(news("005930,000660,반도체", "A사, 1200억 유상증자 결정", null));
        assertThat(exclusion.excludedSymbols(SINCE))
                .containsExactlyInAnyOrder("005930", "000660", "반도체");
    }

    @Test
    void 깨진_facts는_제목_가드로만_판정한다() {
        given(news("195870", "OO사, 50억원 제3자배정 유상증자", "깨진 JSON"));
        assertThat(exclusion.excludedSymbols(SINCE)).containsExactly("195870");
    }

    @Test
    void 타겟이_비면_무시한다() {
        given(news(null, "A사, 1200억 유상증자", null), news("  ", "B사, 1200억 유상증자", null));
        assertThat(exclusion.excludedSymbols(SINCE)).isEmpty();
    }

    @Test
    void 같은_종목_리스크_기사가_여러건이어도_한번만_담긴다() {
        given(news("195870", "A사, 140억 유상증자", null), news("195870", "A사, 200억 유상증자", null));
        assertThat(exclusion.excludedSymbols(SINCE)).containsExactly("195870");
    }

    @Test
    void since가_null이면_예외() {
        assertThatThrownBy(() -> exclusion.excludedSymbols(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 조회_예외는_전파된다() {
        // fail-closed — 호출부가 사이클을 포기해야 한다.
        when(newsMapper.riskCandidatesSince(any())).thenThrow(new RuntimeException("DB 장애"));
        assertThatThrownBy(() -> exclusion.excludedSymbols(SINCE))
                .isInstanceOf(RuntimeException.class);
    }
}
