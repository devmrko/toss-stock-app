package com.cloudhandson.tossstock.market;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SectorClassifierTest {

    private final SectorClassifier c = new SectorClassifier();

    @Test
    void classifies_representative_stocks() {
        // 삼성전자: 업종=통신장비지만 제품에 반도체 → 반도체 우선
        assertThat(c.classify("삼성전자", "통신 및 방송 장비 제조업", "통신장비, 반도체 제조")).isEqualTo("반도체");
        assertThat(c.classify("SK하이닉스", "반도체 제조업", "반도체,컴퓨터")).isEqualTo("반도체");
        assertThat(c.classify("삼성바이오로직스", "기초 의약물질 제조업", "바이오의약품")).isEqualTo("바이오");
        assertThat(c.classify("셀트리온", "기초 의약물질 제조업", "램시마, 트룩시마")).isEqualTo("바이오");
        assertThat(c.classify("현대건설", "토목 건설업", "공사수입,주택분양")).isEqualTo("건설");
        assertThat(c.classify("GS건설", "건물 건설업", "토목공사,건축공사")).isEqualTo("건설");
        assertThat(c.classify("아모레퍼시픽", "기타 화학제품 제조업", "화장품,생활용품")).isEqualTo("화장품");
        assertThat(c.classify("하이브", "오디오물 출판 및 원판 녹음업", "음악 기획/제작, 아티스트 매니지먼트")).isEqualTo("엔터");
        assertThat(c.classify("JYP Ent.", "오디오물 출판 및 원판 녹음업", "오디오물")).isEqualTo("엔터");
        assertThat(c.classify("크래프톤", "소프트웨어 개발 및 공급업", "게임 소프트웨어")).isEqualTo("게임");
        assertThat(c.classify("현대자동차", "자동차용 엔진 및 자동차 제조업", "자동차,부품")).isEqualTo("자동차");
    }

    @Test
    void unknown_falls_back_to_etc() {
        assertThat(c.classify("알수없는회사", "기타 전문 서비스업", "")).isEqualTo("기타");
        assertThat(c.classify(null, null, null)).isEqualTo("기타");
    }
}
