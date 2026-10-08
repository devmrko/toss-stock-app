package com.cloudhandson.tossstock.autotrade;

import com.cloudhandson.tossstock.news.NewsFacts;

/**
 * 뉴스 1건이 <b>투자원칙 §3 기준의 매수 촉매 자격</b>을 갖추는지 판정(순수 함수, #865).
 * 설계: docs/design/865-news-catalyst-qualifier/fn-qualify.md
 *
 * <p>왜 필요한가: 기존 게이트는 {@code CandidateDiscoveryService} 의 {@code level >= 4}
 * 한 줄이었고 실측 통과율이 37%(870기사 → EVENT+S4↑ 324)로 사실상 무필터였다.
 * {@code "목표가↑"-iM}(애널리스트 의견), {@code 발행어음 특판}(상품 홍보),
 * {@code [특징주] 강세}(사후 보도)가 전부 "호재"로 통과했다.
 *
 * <p>원칙 문서(docs/reference/investment-principles.md) §3 체크리스트 7항목 중 뉴스가
 * 기여하는 것은 1번의 '구체 촉매' 하나뿐이다 — 나머지 6개는 회사 속성이다. 그래서 이
 * 클래스는 <b>촉매 자격만</b> 보고, 종목 판정({@code FundamentalScore},
 * {@code ValuationChecker}, {@code PriceExtension})은 건드리지 않는다.
 *
 * <p>임계값·설정 키가 없는 것은 의도된 것이다 — 설정화하면 게이트 우회 경로가 생긴다.
 */
public final class CatalystQualifier {

    /** 판정 결과. reason 은 통과·탈락 모두 채운다(로그·화면의 설명 가능성). */
    public record Verdict(boolean pass, String reason, int score) {
    }

    private static final String PASS_REASON = "자격 충족";

    private CatalystQualifier() {
    }

    /**
     * 촉매 자격 판정. 심각도 순으로 평가해 <b>첫 번째로 걸린 사유만</b> 반환한다.
     *
     * <p>null 처리는 비대칭이다(설계 fn-qualify.md §6):
     * <ul>
     *   <li><b>매수 근거</b>(confirmed·materialAmount·거래 기사의 beneficiary)의 null
     *       → <b>탈락</b>. 근거 부재는 매수 금지다.</li>
     *   <li><b>차단 근거</b>(riskFlag·priceAlreadyMoved)의 null → <b>차단하지 않음</b>.
     *       null 을 "리스크 있음"으로 읽으면 추출이 조금만 불안해도 전 기사가 막힌다.</li>
     * </ul>
     *
     * @param facts 추출된 사실. <b>null 허용</b> — 추출 전면 실패 신호이며 fail-closed.
     * @param title 기사 제목. null 허용 — 그때는 제목 가드가 작동하지 않는다.
     */
    public static Verdict qualify(NewsFacts facts, String title) {
        if (facts == null) {
            return new Verdict(false, "사실 추출 실패", 0);
        }
        int score = score(facts);

        // 0. 결정론적 제목 가드(#869) — LLM 판정보다 먼저. 라이브에서 LLM 이 인수 주체를
        //    SELLER 로, 2조 적자를 riskFlag=NONE 으로 응답한 사례가 실제로 후보 등록까지 갔다.
        //    정규식으로 결정되는 것을 LLM 에 묻지 않는다. 차단만 추가하므로 안전 방향이 단조롭다.
        //    #877: 적자 외에 유상증자·감자·횡령·상폐까지 확장 — 유상증자가 적자보다 6배 많은데
        //    가드가 없었고 LLM riskFlag 는 98% 생략된다.
        String titleRisk = TitleGuard.riskFlagOf(title);
        if (titleRisk != null) {
            return new Verdict(false, "리스크 이벤트(" + titleRisk + "·제목판정)", score);
        }
        if (TitleGuard.buyerSide(title)) {
            return new Verdict(false, "수혜 주체 아님(BUYER·제목판정)", score);
        }

        // 1. 리스크 이벤트 — 긍정 사실이 함께 있어도 상쇄되지 않는다(원칙 §3-3, §3-5).
        String risk = facts.riskFlag();
        if (risk != null && !"NONE".equalsIgnoreCase(risk)) {
            return new Verdict(false, "리스크 이벤트(" + risk + ")", score);
        }
        // 2. 선반영 — 이미 오른 뒤 진입은 실측된 손실 경로다.
        if (Boolean.TRUE.equals(facts.priceAlreadyMoved())) {
            return new Verdict(false, "선반영·사후보도", score);
        }
        // 3. 미확정 — 전망·목표가·추진·검토는 촉매가 아니다(원칙 §3-1).
        if (!Boolean.TRUE.equals(facts.confirmed())) {
            return new Verdict(false, "미확정(전망·추측)", score);
        }
        // 4. 규모 미제시 — 크기 없는 촉매는 촉매가 아니다(원칙 §3-1 '구체 촉매').
        if (!Boolean.TRUE.equals(facts.materialAmount())) {
            return new Verdict(false, "규모 미제시", score);
        }
        // 5. 수혜 주체 — 거래 기사에서 돈 쓰는 쪽은 호재가 아니다(#860).
        //    거래가 아닌 기사(실적 발표·승인)엔 수혜 주체 개념이 없으므로 적용하지 않는다
        //    — 적용하면 같은 실적이 제목 표현에 따라 갈린다(#865 교정 A).
        if (Boolean.TRUE.equals(facts.isTransaction())) {
            String who = facts.beneficiary();
            if (!"SELLER".equalsIgnoreCase(who == null ? "" : who)) {
                return new Verdict(false, "수혜 주체 아님(" + (who == null ? "미상" : who) + ")", score);
            }
        }
        return new Verdict(true, PASS_REASON, score);
    }

    /**
     * 우선순위 점수 0~4 — 원칙 §3 '불변 × 수출' 프레임에 가까운 촉매를 먼저 보기 위한 정렬 값.
     * <b>게이트가 아니다</b>: 원칙 문서는 "대부분 YES"를 요구하고 전부를 요구하지 않는다.
     */
    public static int score(NewsFacts f) {
        if (f == null) {
            return 0;
        }
        return yes(f.recurring()) + yes(f.secularDemand())
                + yes(f.exportGlobal()) + yes(f.shareholderReturn());
    }

    private static int yes(Boolean b) {
        return Boolean.TRUE.equals(b) ? 1 : 0;
    }
}
