package com.cloudhandson.tossstock.autotrade;

import com.cloudhandson.tossstock.news.StockNews;
import com.cloudhandson.tossstock.news.StockNewsMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * #872 논거 무효 판정. 설계: docs/design/872-risk-event-exit/README.md §5
 */
class RiskEventDetectorTest {

    private static final String RISK_FACTS =
            "{\"confirmed\":true,\"materialAmount\":true,\"riskFlag\":\"DILUTION\"}";
    private static final String CLEAN_FACTS =
            "{\"confirmed\":true,\"materialAmount\":true,\"riskFlag\":\"NONE\"}";

    private StockNewsMapper newsMapper;
    private RiskEventDetector detector;

    @BeforeEach
    void setUp() {
        newsMapper = mock(StockNewsMapper.class);
        detector = new RiskEventDetector(newsMapper);
    }

    private static StockNews news(String title, String facts) {
        StockNews n = new StockNews();
        n.setTitle(title);
        n.setFacts(facts);
        return n;
    }

    // ---- judge (순수) ----

    @Test
    void judge_riskFlag가_있으면_그_종류를_반환한다() {
        assertThat(RiskEventDetector.judge("OO전자, 1200억 규모 유상증자 결정", RISK_FACTS))
                .isEqualTo("DILUTION");
    }

    @Test
    void judge_riskFlag가_NONE이어도_공시형_제목이면_그_종류를_반환한다() {
        // 인수조건 2 — LLM 이 놓쳐도 제목 가드가 잡는 이중 구조.
        //
        // #877 에서 이 테스트의 기대가 바뀌었다. 원래는 "2조 적자" 제목으로 LOSS 를
        // 반환해 매도를 유발하는 것이 기대였다(#872). 그런데 라이브 7,655건 전수 검증에서
        // 제목 기반 LOSS 15건 중 4~5건이 오탐이었다 — "'3년간 3.5조 적자' 한화오션,
        // 올 2조 이익 눈앞"(흑자 전환 기사), "SK하닉 … '적자 때 임금 조정' 발칵"(임금협상),
        // "LG화학 … 진에어는 적자 지속"(타 종목 교차오염).
        //
        // 매수 차단의 오탐은 기회비용이지만 매도 오탐은 멀쩡한 포지션의 실현손실이다.
        // 그래서 매도 트리거는 정밀도가 검증된 공시형(DILUTION)만 쓴다.
        assertThat(RiskEventDetector.judge("A사, 140억원 제3자배정 유상증자", CLEAN_FACTS))
                .isEqualTo("DILUTION");
    }

    @Test
    void judge_facts가_없어도_제목_가드는_동작한다() {
        assertThat(RiskEventDetector.judge("OO사, 50억원 제3자배정 유상증자", null)).isEqualTo("DILUTION");
        assertThat(RiskEventDetector.judge("OO사, 50억원 제3자배정 유상증자", "깨진 JSON"))
                .isEqualTo("DILUTION");
    }

    @Test
    void judge_정상_기사는_null() {
        assertThat(RiskEventDetector.judge("HD건설기계, 3900억 규모 수주", CLEAN_FACTS)).isNull();
        assertThat(RiskEventDetector.judge("OO사, 3분기 흑자전환 성공", CLEAN_FACTS)).isNull();
        assertThat(RiskEventDetector.judge("OO사, 적자 축소…턴어라운드 기대", CLEAN_FACTS)).isNull();
        assertThat(RiskEventDetector.judge(null, CLEAN_FACTS)).isNull();
    }

    // ---- scanFrom (순수) ----

    @Test
    void scanFrom_진입이_24시간_이내면_진입시각() {
        LocalDateTime now = LocalDateTime.of(2026, 10, 8, 20, 0);
        LocalDateTime entry = now.minusHours(3);
        assertThat(RiskEventDetector.scanFrom(entry, now)).isEqualTo(entry);
    }

    @Test
    void scanFrom_진입이_24시간보다_오래되면_바닥값() {
        // 인수조건 8 — 장기 보유 포지션에서 stock_news 전체 스캔을 막는다.
        LocalDateTime now = LocalDateTime.of(2026, 10, 8, 20, 0);
        assertThat(RiskEventDetector.scanFrom(now.minusDays(30), now))
                .isEqualTo(now.minusHours(RiskEventDetector.LOOKBACK_HOURS));
    }

    @Test
    void scanFrom_진입시각이_null이면_바닥값() {
        LocalDateTime now = LocalDateTime.of(2026, 10, 8, 20, 0);
        assertThat(RiskEventDetector.scanFrom(null, now))
                .isEqualTo(now.minusHours(RiskEventDetector.LOOKBACK_HOURS));
    }

    // ---- detect (조회 포함) ----

    @Test
    void detect_리스크_기사가_있으면_종류와_제목을_반환한다() {
        when(newsMapper.forSymbolBetween(eq("005930"), any(), any())).thenReturn(List.of(
                news("삼성전자, 신제품 공개", CLEAN_FACTS),
                news("삼성전자, 1200억 유상증자 결정", RISK_FACTS)));

        RiskVerdict v = detector.detect("005930", LocalDateTime.now().minusHours(2));

        assertThat(v).isNotNull();
        assertThat(v.flag()).isEqualTo("DILUTION");
        assertThat(v.title()).contains("유상증자");
    }

    @Test
    void detect_리스크_기사가_없으면_null() {
        // 인수조건 4 — #871 회귀 방지의 기반. 리스크가 없으면 팔지 않는다.
        when(newsMapper.forSymbolBetween(any(), any(), any())).thenReturn(List.of(
                news("삼성전자, 3900억 규모 수주", CLEAN_FACTS)));

        assertThat(detector.detect("005930", LocalDateTime.now().minusHours(2))).isNull();
    }

    @Test
    void detect_조회_하한이_scanFrom_값이다() {
        // 인수조건 3·8 — 진입 이전 기사는 조회 자체에서 배제된다.
        LocalDateTime entry = LocalDateTime.now().minusHours(2);
        when(newsMapper.forSymbolBetween(any(), any(), any())).thenReturn(List.of());

        detector.detect("005930", entry);

        ArgumentCaptor<LocalDateTime> from = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(newsMapper).forSymbolBetween(eq("005930"), from.capture(), any());
        assertThat(from.getValue()).isEqualTo(entry);
    }

    // ---- #877 매도 트리거는 공시형(DILUTION)만 ----

    @Test
    void judge_유상증자_제목은_riskFlag_생략이어도_DILUTION() {
        assertThat(RiskEventDetector.judge("에피소드컴퍼니, 140억원 제3자배정 유상증자", CLEAN_FACTS))
                .isEqualTo("DILUTION");
        assertThat(RiskEventDetector.judge("알테오젠, 500억원 유상증자…제3자배정", null))
                .isEqualTo("DILUTION");
    }

    @Test
    void judge_제목_기반_적자는_매도를_유발하지_않는다() {
        // #877 — 라이브 전수에서 LOSS 15건 중 4~5건이 오탐이었다("'3년간 3.5조 적자'
        // 한화오션, 올 2조 이익 눈앞" 등). 매수 차단의 오탐은 기회비용이지만
        // 매도 오탐은 멀쩡한 포지션의 실현손실이다.
        assertThat(RiskEventDetector.judge("갤Z 폴드8 흥행에도…DX부문, 2조 적자", CLEAN_FACTS)).isNull();
        assertThat(RiskEventDetector.judge("'3년간 3.5조 적자' 한화오션, 올 2조 이익 눈앞", null)).isNull();
    }

    @Test
    void judge_제목_기반_상폐는_매도를_유발하지_않는다() {
        // DELISTING 은 오탐이 10건 중 5건이었다 — 제목 정규식은 "누구의 리스크인지"를
        // 구분하지 못한다("홈플러스 회생절차 폐지에…'반사이익' 롯데쇼핑·이마트 강세").
        assertThat(RiskEventDetector.judge("홈플러스 회생절차 폐지에…'반사이익' 롯데쇼핑·이마트 강세", null)).isNull();
        assertThat(RiskEventDetector.judge("파산 위기 '동전주' 하이닉스, 25년만에 증시 새 황제 등극", null)).isNull();
    }

    @Test
    void judge_LLM이_riskFlag를_채웠으면_종류_무관하게_매도한다() {
        // 제목 가드와 달리 LLM 이 명시적으로 플래그를 준 경우는 그대로 신뢰한다(#872).
        String governance = "{\"riskFlag\":\"GOVERNANCE\"}";
        assertThat(RiskEventDetector.judge("A사 전 대표 기소", governance)).isEqualTo("GOVERNANCE");
    }
}
