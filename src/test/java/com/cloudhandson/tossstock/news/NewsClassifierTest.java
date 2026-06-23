package com.cloudhandson.tossstock.news;

import com.cloudhandson.tossstock.market.UniverseMapper;
import com.cloudhandson.tossstock.news.NewsClassifier.ClassifyResult;
import com.cloudhandson.tossstock.news.NewsClassifier.Parsed;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class NewsClassifierTest {

    private final UniverseMapper um = mock(UniverseMapper.class);
    private final NewsProperties props = new NewsProperties(List.of(), "", "m", "u", 1000, 40);
    private final NewsClassifier c = new NewsClassifier(props, um);

    @Test
    void parses_multi_target_json_with_fences() {
        Parsed p = c.parse("""
                ```json
                {"targets":[{"type":"SYMBOL","name":"삼성전자","level":"S5"},
                            {"type":"SECTOR","name":"반도체","level":"S5"}],
                 "kind":"EVENT","analysis":"HBM 호재"}
                ```""");
        assertThat(p).isNotNull();
        assertThat(p.targets()).hasSize(2);
        assertThat(p.kind()).isEqualTo("EVENT");
    }

    @Test
    void resolves_symbol_name_to_code_and_builds_sentiment() {
        when(um.findCodeByName("삼성전자")).thenReturn("005930");
        Parsed p = new Parsed(List.of(
                new NewsClassifier.Target("SYMBOL", "삼성전자", "S5"),
                new NewsClassifier.Target("SECTOR", "반도체", "S5"),
                new NewsClassifier.Target("MARKET", "MARKET", "S3")), "EVENT", "분석");

        ClassifyResult r = c.resolve(p);

        assertThat(r.targetsCsv()).isEqualTo("005930,반도체,MARKET");
        assertThat(r.sentimentCsv()).isEqualTo("005930:S5,반도체:S5,MARKET:S3");
        assertThat(r.maxStrength()).isEqualTo(2);
    }

    @Test
    void drops_unknown_symbol_and_invalid_sector() {
        when(um.findCodeByName("없는회사")).thenReturn(null);
        Parsed p = new Parsed(List.of(
                new NewsClassifier.Target("SYMBOL", "없는회사", "S5"),
                new NewsClassifier.Target("SECTOR", "헛소리섹터", "S4")), "EVENT", "x");

        ClassifyResult r = c.resolve(p);

        // 둘 다 해석 실패 → 기본 MARKET:S3
        assertThat(r.targetsCsv()).isEqualTo("MARKET");
        assertThat(r.sentimentCsv()).isEqualTo("MARKET:S3");
        assertThat(r.maxStrength()).isZero();
    }

    @Test
    void parse_failure_returns_null() {
        assertThat(c.parse("not json at all")).isNull();
    }

    @Test
    void strength_mapping() {
        assertThat(NewsClassifier.strength("S1")).isEqualTo(2);
        assertThat(NewsClassifier.strength("S5")).isEqualTo(2);
        assertThat(NewsClassifier.strength("S4")).isEqualTo(1);
        assertThat(NewsClassifier.strength("S3")).isZero();
    }
}
