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

    // ---- #877 리스크 종류 확장 ----

    @Test
    void 유상증자는_DILUTION() {
        // 라이브 실제 제목. 종목코드 타겟 기사 99건으로 적자(16건)보다 6배 많은데
        // #869 시점엔 가드가 없었다.
        assertThat(TitleGuard.riskFlagOf("에피소드컴퍼니, 140억원 제3자배정 유상증자")).isEqualTo("DILUTION");
        assertThat(TitleGuard.riskFlagOf("알테오젠, 500억원 유상증자…스카이알트 유한회사에 제3자배정")).isEqualTo("DILUTION");
        assertThat(TitleGuard.riskFlagOf("현대건설, 5천억원 CB 발행 완료")).isEqualTo("DILUTION");
        assertThat(TitleGuard.riskFlagOf("핑거, 150억 전환사채 발행")).isEqualTo("DILUTION");
    }

    @Test
    void 무상증자는_차단대상_아님() {
        // 인수조건 4 — 무상증자는 호재다. '증자' 단독 패턴을 쓰지 않는 이유.
        assertThat(TitleGuard.riskFlagOf("큐리오시스, 주당 1.0주 무상증자 결정")).isNull();
        assertThat(TitleGuard.riskFlagOf("남성, 30% 무상증자 결정 소식에 '급등'")).isNull();
    }

    @Test
    void 철회된_유상증자는_차단대상_아님() {
        // 인수조건 3
        assertThat(TitleGuard.riskFlagOf("A사, 유상증자 철회 결정")).isNull();
        assertThat(TitleGuard.riskFlagOf("B사, 전환사채 발행 취소")).isNull();
    }

    @Test
    void 감자는_식품기사를_잡지_않는다() {
        // 인수조건 5 — '감자' 단독 패턴을 쓰지 않는 이유(라이브 실제 제목).
        assertThat(TitleGuard.riskFlagOf("크라운제과 찐감자 스낵 어썸, 네 번째 에디션 '체다치즈' 출시")).isNull();
        assertThat(TitleGuard.riskFlagOf("오리온 '미쯔 황치즈'·해태-가루비 '연어초밥맛' 감자칩")).isNull();
        // 실제 자본 감소는 잡는다
        assertThat(TitleGuard.riskFlagOf("C사, 무상감자 결정")).isEqualTo("IMPAIRMENT");
        assertThat(TitleGuard.riskFlagOf("D사, 자본잠식 50% 초과")).isEqualTo("IMPAIRMENT");
    }

    @Test
    void 횡령_분식은_GOVERNANCE() {
        assertThat(TitleGuard.riskFlagOf("금융당국, 금양 '분식회계 혐의'로 검찰 고발조치")).isEqualTo("GOVERNANCE");
        assertThat(TitleGuard.riskFlagOf("E사 전 대표, 횡령 혐의로 기소")).isEqualTo("GOVERNANCE");
    }

    @Test
    void 상폐_관리종목은_DELISTING() {
        assertThat(TitleGuard.riskFlagOf("'시총 미달' 프롬바이오, 관리종목 지정에 '급락'")).isEqualTo("DELISTING");
        // 따옴표가 끼어도, '상폐' 약어만 있어도 잡아야 한다 — 처음 패턴은 둘 다 놓쳤다.
        assertThat(TitleGuard.riskFlagOf("제이알글로벌리츠, 감사의견 '거절'…상폐 사유 발생")).isEqualTo("DELISTING");
        assertThat(TitleGuard.riskFlagOf("H사, 상폐 사유 발생")).isEqualTo("DELISTING");
    }

    @Test
    void 거래정지_해제는_차단대상_아님() {
        // 인수조건 6
        assertThat(TitleGuard.riskFlagOf("F사, 거래정지 해제")).isNull();
    }

    @Test
    void 심각도_순서대로_판정한다() {
        // 상장폐지 + 적자 동시 → 존속 위험이 위다.
        assertThat(TitleGuard.riskFlagOf("G사, 적자 누적에 상장폐지 사유 발생")).isEqualTo("DELISTING");
    }

    @Test
    void 적자_축소는_중간에_숫자가_끼어도_제외된다() {
        // #877 교정 — 이전 패턴은 '적자\s?축소' 라 "적자 79% 축소" 를 놓쳤다(라이브 오탐).
        assertThat(TitleGuard.riskFlagOf("SKC, 2분기 적자 79% 축소…동박 매출은 역대 최대")).isNull();
        assertThat(TitleGuard.riskFlagOf("한화오션, 해양 적자 고리 끊는다…30억 달러 수주 총력전")).isNull();
        assertThat(TitleGuard.lossSide("SKC, 2분기 적자 79% 축소")).isFalse();
    }

    @Test
    void 정상_호재는_어떤_리스크도_아니다() {
        // 인수조건 8
        String[] ok = {
                "HD건설기계, 美 데이터센터 발전엔진 '롱블록' 수주…3900억 규모",
                "LG에너지솔루션, 3분기 영업익 7560억원…분기 최대 매출",
                "HDC, 자기주식 771억원 상당 소각 결정",
                "유니온바이오메트릭스, 171억원 육군 출입통제 사업 수주",
        };
        for (String t : ok) {
            assertThat(TitleGuard.riskFlagOf(t)).as("riskFlagOf: %s", t).isNull();
        }
    }

    @Test
    void riskFlagOf_는_null과_빈_제목에_null() {
        assertThat(TitleGuard.riskFlagOf(null)).isNull();
        assertThat(TitleGuard.riskFlagOf("  ")).isNull();
    }

    // ---- #877 매도 트리거는 DILUTION 만 ----

    @Test
    void 매도_트리거는_DILUTION만_인정한다() {
        // 매수 차단의 오탐은 기회비용이지만 매도 오탐은 실현손실이다.
        // 라이브 전수에서 DILUTION 은 101건 오탐 0~2건, DELISTING 은 10건 중 5건이 오탐이었다.
        assertThat(TitleGuard.announcedRiskOf("에피소드컴퍼니, 140억원 제3자배정 유상증자")).isEqualTo("DILUTION");
        assertThat(TitleGuard.announcedRiskOf("'시총 미달' 프롬바이오, 관리종목 지정에 '급락'")).isNull();
        assertThat(TitleGuard.announcedRiskOf("갤Z 폴드8 흥행에도…DX부문, 2조 적자")).isNull();
        assertThat(TitleGuard.announcedRiskOf("금양 '분식회계 혐의'로 검찰 고발조치")).isNull();
    }

    @Test
    void 매도_트리거에서_제외된_유형도_매수는_계속_차단된다() {
        // 비대칭이 의도된 것이다.
        String delisting = "'시총 미달' 프롬바이오, 관리종목 지정에 '급락'";
        assertThat(TitleGuard.announcedRiskOf(delisting)).isNull();
        assertThat(TitleGuard.riskFlagOf(delisting)).isEqualTo("DELISTING");
    }
}
