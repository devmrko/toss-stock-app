package com.cloudhandson.tossstock.autotrade;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #869 결정론적 제목 가드. 설계: docs/design/869-title-guard-and-candidate-requalify/README.md §5
 * 제목은 라이브에서 실제로 수집된 것을 우선 사용한다.
 */
class TitleGuardTest {

    @Test
    void buyer_수령_동사는_차단대상() {
        assertThat(TitleGuard.buyerSide("LG엔솔, 캐나다산 리튬 24t 공급받기로…\"북미 공급망 강화\"")).isTrue();
        assertThat(TitleGuard.buyerSide("A사, 핵심 부품 납품받아 생산 확대")).isTrue();
        assertThat(TitleGuard.buyerSide("B사, 기술 제공받는다")).isTrue();
    }

    @Test
    void buyer_인수_주체는_차단대상() {
        // 라이브 결함 사례 — LLM 이 beneficiary=SELLER 로 응답해 후보 등록까지 갔다.
        assertThat(TitleGuard.buyerSide("HD현대마린솔루션, 美 엔진 기업 '골텐스' 3315억원에 인수")).isTrue();
        assertThat(TitleGuard.buyerSide("C사, D사 지분 51% 인수")).isTrue();
    }

    @Test
    void buyer_피인수는_차단대상_아님() {
        // 주체가 매각·피인수 측이면 돈 쓰는 쪽이 아니다.
        assertThat(TitleGuard.buyerSide("E사, 글로벌 F사에 인수돼")).isFalse();
        assertThat(TitleGuard.buyerSide("G사, H그룹에 인수됐다")).isFalse();
        assertThat(TitleGuard.buyerSide("I사, 피인수 절차 마무리")).isFalse();
    }

    @Test
    void buyer_자사주_매입은_주주환원이므로_차단대상_아님() {
        assertThat(TitleGuard.buyerSide("HDC, 자기주식 771억원 상당 소각 결정…\"주주환원 강화\"")).isFalse();
        assertThat(TitleGuard.buyerSide("J사, 자사주 500억원 매입 결정")).isFalse();
        // 자사주 맥락이 아닌 매입은 돈 쓰는 쪽.
        assertThat(TitleGuard.buyerSide("K사, 美 공장 부지 매입")).isTrue();
    }

    @Test
    void buyer_수주_공급은_차단대상_아님() {
        // 인수조건 4 — 매출이 생기는 쪽은 막지 않는다.
        assertThat(TitleGuard.buyerSide("HD건설기계, 美 데이터센터 발전엔진 '롱블록' 수주…3900억 규모")).isFalse();
        assertThat(TitleGuard.buyerSide("아이티센엔텍, 2137억 'K-에듀파인 고도화' 수주")).isFalse();
        assertThat(TitleGuard.buyerSide("엠플러스, 유럽 글로벌 배터리 기업에 각형 장비 수주")).isFalse();
        assertThat(TitleGuard.buyerSide("L사, 삼성SDI에 양극재 공급계약 체결")).isFalse();
        assertThat(TitleGuard.buyerSide("M사, 발주받아 납품 개시")).isFalse();
    }

    @Test
    void buyer_발주는_차단대상() {
        assertThat(TitleGuard.buyerSide("N사, 신규 설비 발주")).isTrue();
    }

    @Test
    void loss_적자_손실은_차단대상() {
        // 라이브 결함 사례 — riskFlag=NONE 으로 응답해 자격 통과했다.
        assertThat(TitleGuard.lossSide("갤Z 폴드8 흥행에도…DX부문, 2조 적자")).isTrue();
        assertThat(TitleGuard.lossSide("O사, 3분기 영업손실 확대")).isTrue();
        assertThat(TitleGuard.lossSide("P사, 순손실 1200억")).isTrue();
        assertThat(TitleGuard.lossSide("Q사 어닝쇼크…시장 예상 크게 밑돌아")).isTrue();
        assertThat(TitleGuard.lossSide("R사 어닝 쇼크")).isTrue();
    }

    @Test
    void loss_전환_개선_표현은_차단대상_아님() {
        // 인수조건 3 — 실제로는 호재다.
        assertThat(TitleGuard.lossSide("S사, 3분기 흑자전환 성공")).isFalse();
        assertThat(TitleGuard.lossSide("T사, 흑자 전환…체질 개선 효과")).isFalse();
        assertThat(TitleGuard.lossSide("U사, 적자 축소…하반기 턴어라운드 기대")).isFalse();
        assertThat(TitleGuard.lossSide("V사, 3년 만에 적자 탈출")).isFalse();
    }

    @Test
    void 정상_호재_제목은_양쪽_가드_모두_통과() {
        String[] ok = {
                "HD건설기계, 美 데이터센터 발전엔진 '롱블록' 수주…3900억 규모",
                "LG에너지솔루션, 3분기 영업익 7560억원…분기 최대 매출",
                "TSMC, 3분기 매출 62조원 '사상 최대'…AI 반도체 수요가 견인",
                "유니온바이오메트릭스, 171억원 육군 출입통제 사업 수주",
        };
        for (String t : ok) {
            assertThat(TitleGuard.buyerSide(t)).as("buyerSide: %s", t).isFalse();
            assertThat(TitleGuard.lossSide(t)).as("lossSide: %s", t).isFalse();
        }
    }

    @Test
    void null과_빈제목은_차단하지_않는다() {
        // 차단 근거 부재는 차단하지 않는다(#865 비대칭 null 규칙과 같은 방향).
        assertThat(TitleGuard.buyerSide(null)).isFalse();
        assertThat(TitleGuard.lossSide(null)).isFalse();
        assertThat(TitleGuard.buyerSide("  ")).isFalse();
        assertThat(TitleGuard.lossSide("")).isFalse();
    }
}
