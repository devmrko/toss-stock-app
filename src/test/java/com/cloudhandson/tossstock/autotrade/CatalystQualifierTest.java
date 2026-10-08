package com.cloudhandson.tossstock.autotrade;

import com.cloudhandson.tossstock.news.NewsFacts;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #865 촉매 자격 판정. 설계: docs/design/865-news-catalyst-qualifier/fn-qualify.md §10
 */
class CatalystQualifierTest {

    /** 제목 가드(#869)가 작동하지 않는 중립 제목 — facts 규칙만 보고 싶을 때. */
    private static final String NEUTRAL = "A사, 3900억원 규모 공급계약 체결";

    /** 통과 기준선: 확정 + 실적금액 + 거래의 수혜주체 + 리스크 없음. */
    private static NewsFacts good() {
        return new NewsFacts(true, true, true, false, false, false, false, false,
                "SELLER", "NONE", "확정 수주");
    }

    @Test
    void 자격_충족() {
        CatalystQualifier.Verdict v = CatalystQualifier.qualify(good(), NEUTRAL);
        assertThat(v.pass()).isTrue();
        assertThat(v.reason()).isEqualTo("자격 충족");
    }

    // ---- #869 결정론적 제목 가드 ----

    @Test
    void 가드_LLM이_SELLER라고_해도_인수_주체면_차단된다() {
        // 인수조건 1 — 라이브 결함: HD현대마린솔루션이 3315억을 지출하는 인수 주체인데
        // beneficiary=SELLER 로 응답해 후보 등록까지 갔다.
        CatalystQualifier.Verdict v = CatalystQualifier.qualify(
                good(), "HD현대마린솔루션, 美 엔진 기업 '골텐스' 3315억원에 인수");
        assertThat(v.pass()).isFalse();
        assertThat(v.reason()).isEqualTo("수혜 주체 아님(BUYER·제목판정)");
    }

    @Test
    void 가드_riskFlag가_NONE이어도_적자_제목이면_차단된다() {
        // 인수조건 2 — 라이브 결함: "2조 적자" 기사가 riskFlag=NONE 으로 통과했다.
        CatalystQualifier.Verdict v = CatalystQualifier.qualify(
                good(), "갤Z 폴드8 흥행에도…DX부문, 2조 적자");
        assertThat(v.pass()).isFalse();
        assertThat(v.reason()).isEqualTo("리스크 이벤트(LOSS·제목판정)");
    }

    @Test
    void 가드_정상_수주_제목은_기존대로_통과한다() {
        // 인수조건 6 — 가드는 차단만 추가한다. 통과하던 기사를 막지 않는다.
        assertThat(CatalystQualifier.qualify(
                good(), "HD건설기계, 美 데이터센터 발전엔진 '롱블록' 수주…3900억 규모").pass()).isTrue();
        assertThat(CatalystQualifier.qualify(
                good(), "S사, 3분기 흑자전환 성공").pass()).isTrue();
    }

    @Test
    void 가드_제목이_null이면_facts_규칙만_적용된다() {
        assertThat(CatalystQualifier.qualify(good(), null).pass()).isTrue();
    }

    @Test
    void 가드는_facts_규칙보다_먼저_평가된다() {
        // 제목이 적자인데 facts 는 선반영이라고 할 때, 사유는 제목 가드 쪽이다.
        NewsFacts moved = new NewsFacts(true, true, true, false, false, false, false, true,
                "SELLER", "NONE", "선반영");
        assertThat(CatalystQualifier.qualify(moved, "O사, 3분기 영업손실 확대").reason())
                .isEqualTo("리스크 이벤트(LOSS·제목판정)");
    }

    @Test
    void 규칙1_리스크이벤트는_긍정사실이_있어도_차단된다() {
        // 인수조건 2 — 유상증자는 강한 호재와 함께 와도 상쇄되지 않는다(원칙 §3-3).
        NewsFacts f = new NewsFacts(true, true, true, true, true, true, true, false,
                "SELLER", "DILUTION", "대규모 수주와 동시에 유상증자 결정");
        CatalystQualifier.Verdict v = CatalystQualifier.qualify(f, NEUTRAL);
        assertThat(v.pass()).isFalse();
        assertThat(v.reason()).contains("DILUTION");
        assertThat(v.score()).isEqualTo(4);   // 점수가 만점이어도 통과시키지 않는다
    }

    @Test
    void 규칙2_선반영_사후보도는_차단된다() {
        NewsFacts f = new NewsFacts(true, true, true, false, false, false, false, true,
                "SELLER", "NONE", "특징주 급등 사후 보도");
        CatalystQualifier.Verdict v = CatalystQualifier.qualify(f, NEUTRAL);
        assertThat(v.pass()).isFalse();
        assertThat(v.reason()).isEqualTo("선반영·사후보도");
    }

    @Test
    void 규칙3_미확정은_차단된다() {
        NewsFacts f = new NewsFacts(false, true, true, false, false, false, false, false,
                "SELLER", "NONE", "목표가 상향 전망");
        assertThat(CatalystQualifier.qualify(f, NEUTRAL).reason()).isEqualTo("미확정(전망·추측)");
    }

    @Test
    void 규칙4_규모_미제시는_차단된다() {
        NewsFacts f = new NewsFacts(true, true, false, false, false, false, false, false,
                "SELLER", "NONE", "금액 미공개 MOU");
        assertThat(CatalystQualifier.qualify(f, NEUTRAL).reason()).isEqualTo("규모 미제시");
    }

    @Test
    void 규칙5_거래기사의_구매자는_차단된다() {
        NewsFacts f = new NewsFacts(true, true, true, false, false, false, false, false,
                "BUYER", "NONE", "리튬을 공급받기로");
        CatalystQualifier.Verdict v = CatalystQualifier.qualify(f, NEUTRAL);
        assertThat(v.pass()).isFalse();
        assertThat(v.reason()).contains("BUYER");
    }

    @Test
    void 교정A_비거래_기사는_수혜주체로_탈락하지_않는다() {
        // 인수조건 6 — 실적 발표엔 수혜 주체 개념이 없다. 실측에서 같은 LG엔솔 3분기 실적인데
        // "매출 9.6조"는 통과, "영업익 7560억"은 수혜주체 아님으로 탈락하던 비일관 제거.
        NewsFacts f = new NewsFacts(true, false, true, false, false, false, false, false,
                "NEITHER", "NONE", "3분기 영업이익 7560억원 발표");
        assertThat(CatalystQualifier.qualify(f, NEUTRAL).pass()).isTrue();
    }

    @Test
    void 사유는_심각도_우선순위를_따른다() {
        // 리스크와 선반영이 동시면 리스크 쪽을 보고한다.
        NewsFacts f = new NewsFacts(true, true, true, false, false, false, false, true,
                "SELLER", "GOVERNANCE", "횡령 혐의 보도 후 급락");
        assertThat(CatalystQualifier.qualify(f, NEUTRAL).reason()).contains("GOVERNANCE");
    }

    @Test
    void 점수는_불변수출_프레임_근접도_0에서_4() {
        NewsFacts all = new NewsFacts(true, true, true, true, true, true, true, false,
                "SELLER", "NONE", "장기 수출 공급계약 + 자사주 소각");
        assertThat(CatalystQualifier.score(all)).isEqualTo(4);
        assertThat(CatalystQualifier.score(good())).isZero();
        assertThat(CatalystQualifier.score(null)).isZero();
    }

    @Test
    void facts가_null이면_매수하지_않는다() {
        // 인수조건 5 — fail-closed. LLM 실패·구버전 행.
        CatalystQualifier.Verdict v = CatalystQualifier.qualify(null, NEUTRAL);
        assertThat(v.pass()).isFalse();
        assertThat(v.reason()).isEqualTo("사실 추출 실패");
        assertThat(v.score()).isZero();
    }

    @Test
    void 전필드_null이면_미확정으로_탈락한다() {
        NewsFacts f = new NewsFacts(null, null, null, null, null, null, null, null, null, null, null);
        assertThat(CatalystQualifier.qualify(f, NEUTRAL).reason()).isEqualTo("미확정(전망·추측)");
    }

    @Test
    void 비대칭규칙_차단근거의_null은_차단하지_않는다() {
        // riskFlag / priceAlreadyMoved 가 "모름"이면 막지 않는다 —
        // null 을 "리스크 있음"으로 읽으면 추출이 조금만 불안해도 전 기사가 막힌다.
        NewsFacts noRisk = new NewsFacts(true, true, true, false, false, false, false, null,
                "SELLER", null, "리스크·선반영 미상");
        assertThat(CatalystQualifier.qualify(noRisk, NEUTRAL).pass()).isTrue();
    }

    @Test
    void 비대칭규칙_매수근거의_null은_탈락시킨다() {
        NewsFacts noConfirm = new NewsFacts(null, true, true, false, false, false, false, false,
                "SELLER", "NONE", "확정 여부 미상");
        assertThat(CatalystQualifier.qualify(noConfirm, NEUTRAL).pass()).isFalse();

        NewsFacts noAmount = new NewsFacts(true, true, null, false, false, false, false, false,
                "SELLER", "NONE", "규모 미상");
        assertThat(CatalystQualifier.qualify(noAmount, NEUTRAL).reason()).isEqualTo("규모 미제시");

        NewsFacts noWho = new NewsFacts(true, true, true, false, false, false, false, false,
                null, "NONE", "거래지만 수혜주체 미상");
        assertThat(CatalystQualifier.qualify(noWho, NEUTRAL).reason()).contains("미상");
    }
}
