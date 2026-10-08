package com.cloudhandson.tossstock.autotrade;

import java.util.regex.Pattern;

/**
 * 기사 제목만으로 <b>확정적으로</b> 판별되는 차단 사유(순수 함수, #869).
 * 설계: docs/design/869-title-guard-and-candidate-requalify/README.md §2.1
 *
 * <p>왜 필요한가: #865 배포 후 라이브에서 LLM 이 두 번 틀렸다.
 * <ul>
 *   <li>{@code HD현대마린솔루션, 美 엔진기업 '골텐스' 3315억원에 인수} →
 *       3315억을 <b>지출하는</b> 인수 주체인데 {@code beneficiary=SELLER} 로 응답,
 *       실제로 후보 등록까지 갔다.</li>
 *   <li>{@code 갤Z 폴드8 흥행에도…DX부문, 2조 적자} →
 *       {@code riskFlag=NONE} 으로 응답해 적자 기사가 자격 통과했다.</li>
 * </ul>
 * 둘 다 제목만 보면 정규식으로 결정되는 사안이다. <b>정규식으로 결정되는 것을 LLM 에
 * 묻지 않는다</b> — LLM 은 "무엇에 관한 기사인가"에 쓰고 "이 단어가 있는가"는 코드가 본다.
 *
 * <p>이 가드는 <b>차단만 추가</b>한다. 통과를 늘리는 경로가 없으므로 가드가 틀려도
 * 손실이 아니라 기회비용만 생긴다(안전 방향이 단조롭다).
 */
public final class TitleGuard {

    /**
     * 대상 종목이 "돈 쓰는 쪽"임을 제목이 확정적으로 보이는 패턴.
     * 피인수(주체가 매각 측)·자사주 매입(주주환원)·발주받음(수주)은 제외한다.
     */
    private static final Pattern BUYER = Pattern.compile(
            "공급\\s?받|납품\\s?받|제공\\s?받|조달\\s?받"
                    + "|(?<!피)인수(?!돼|됐|된|당)"
                    + "|발주(?!\\s?받)");

    /** 자사주/자기주식 매입은 주주환원이므로 BUYER 가 아니다. */
    private static final Pattern SHARE_BUYBACK = Pattern.compile("자사주|자기주식|자기주식취득");

    /** 매입은 자사주 맥락이 아닐 때만 BUYER. */
    private static final Pattern BUY_IN = Pattern.compile("매입");

    /** 적자·손실을 제목이 확정적으로 보이는 패턴(원칙 §3-2 핵심사업 흑자). */
    private static final Pattern LOSS = Pattern.compile(
            "적자|영업손실|영업 손실|순손실|순 손실|어닝\\s?쇼크|실적\\s?쇼크");

    /**
     * 실제로는 호재인 전환·개선 표현 — LOSS 로 보지 않는다.
     *
     * <p>#877 교정: 이전 패턴은 {@code 적자\s?축소} 라 <b>중간에 숫자가 끼면 놓쳤다</b>.
     * 라이브 전수 검증에서 두 건이 오탐으로 걸렸다 —
     * {@code "SKC, 2분기 적자 79% 축소…동박 매출은 역대 최대"}(적자와 축소 사이에 "79% "),
     * {@code "한화오션, 해양 적자 고리 끊는다…30억 달러 수주 총력전"}("고리" 가 끼어 있음).
     * 그래서 사이에 임의 10자를 허용하고 '끊·해소·탈피·벗어' 를 추가했다.
     *
     * <p>이 방향의 과잉 제외는 안전하다 — LOSS 는 RISK_EVENT 매도를 유발하므로(#872)
     * 오탐이 <b>멀쩡한 포지션의 실현손실</b>로 이어진다. 애매하면 팔지 않는 쪽이 맞다.
     */
    private static final Pattern LOSS_POSITIVE = Pattern.compile(
            "흑자\\s?전환"
                    + "|(적자|영업손실|순손실).{0,10}(탈출|탈피|축소|감소|개선|해소|끊|벗어)");

    /**
     * 희석 사건(#877). 라이브 전수에서 종목코드 타겟 기사 99건 — 적자(16건)보다 6배 많은데
     * #869 시점엔 가드가 없었다. LLM riskFlag 는 98% 생략되므로 제목으로 확정해야 한다.
     *
     * <p><b>'증자' 단독을 쓰지 않는 이유</b>: 무상증자는 호재다. '유상증자' 를 명시해 자연히 제외한다.
     */
    private static final Pattern DILUTION = Pattern.compile(
            "유상증자|전환사채|신주인수권|CB\\s?발행|BW\\s?발행");

    /** 철회·취소된 유상증자는 호재다. */
    private static final Pattern DILUTION_CANCELLED = Pattern.compile("철회|취소|중단");

    /**
     * 자본·존속 위험(#877).
     * <p><b>'감자' 단독을 쓰지 않는 이유</b>: 감자튀김·감자칩·감자 농가 등 식품 기사가 잡힌다.
     */
    private static final Pattern IMPAIRMENT = Pattern.compile(
            "무상감자|유상감자|자본감소|자본잠식");

    /** 거버넌스 위험(#877) — 원칙 §3-5 "분쟁·규제·소송 Low". */
    private static final Pattern GOVERNANCE = Pattern.compile("횡령|배임|분식회계");

    /**
     * 상장 존속 위험(#877).
     *
     * <p>{@code 상폐} 약어와, {@code 감사의견 '거절'} 처럼 따옴표가 끼는 경우를 포함한다 —
     * 라이브 제목 {@code "제이알글로벌리츠, 감사의견 '거절'…상폐 사유 발생"} 이
     * {@code 감사의견\s?거절} 패턴으로는 잡히지 않았다.
     */
    private static final Pattern DELISTING = Pattern.compile(
            "상장폐지|상폐|거래정지|관리종목|감사의견.{0,3}(거절|한정)|회생절차|파산");

    /** "거래정지 해제" 는 호재다. */
    private static final Pattern DELISTING_LIFTED = Pattern.compile("해제");

    private TitleGuard() {
    }

    /**
     * 제목이 "대상 종목이 대금을 지출하는 쪽"임을 확정적으로 보이는가.
     * null/빈 제목은 false — 차단 근거 부재는 차단하지 않는다(#865 비대칭 null 규칙).
     */
    public static boolean buyerSide(String title) {
        if (title == null || title.isBlank()) {
            return false;
        }
        if (BUYER.matcher(title).find()) {
            return true;
        }
        // 매입은 자사주 맥락이면 주주환원 — 그때만 통과시킨다.
        return BUY_IN.matcher(title).find() && !SHARE_BUYBACK.matcher(title).find();
    }

    /** 제목이 적자·손실을 확정적으로 보이는가. 흑자전환·적자 축소 등 개선 표현은 제외. */
    public static boolean lossSide(String title) {
        if (title == null || title.isBlank()) {
            return false;
        }
        if (LOSS_POSITIVE.matcher(title).find()) {
            return false;
        }
        return LOSS.matcher(title).find();
    }

    /**
     * 제목만으로 확정되는 리스크 종류(#877). 리스크가 아니면 null.
     * 반환 어휘는 #865 {@code NewsFacts.riskFlag} 와 같아서 호출부가 그대로 재사용한다.
     *
     * <p>왜 필요한가: #869 는 적자만 봤는데, 라이브 전수에서 종목코드 타겟 기사 기준
     * 유상증자 99건 / 상폐·거래정지 10건 / 감자·자본잠식 7건 / 적자 16건이다. 유상증자가
     * 적자보다 6배 많은데 가드가 없었고, LLM riskFlag 는 98% 생략된다. 그 결과
     * "140억원 제3자배정 유상증자" 같은 기사가 confirmed·materialAmount 를 만족해
     * <b>희석 사건이 매수 근거로 통과</b>하고, 보유 중에도 RISK_EVENT 가 발화하지 않았다.
     *
     * <p>평가 순서는 심각도순이다 — 존속 위험이 가장 위다.
     */
    public static String riskFlagOf(String title) {
        if (title == null || title.isBlank()) {
            return null;
        }
        if (DELISTING.matcher(title).find() && !DELISTING_LIFTED.matcher(title).find()) {
            return "DELISTING";
        }
        if (GOVERNANCE.matcher(title).find()) {
            return "GOVERNANCE";
        }
        if (IMPAIRMENT.matcher(title).find()) {
            return "IMPAIRMENT";
        }
        if (DILUTION.matcher(title).find() && !DILUTION_CANCELLED.matcher(title).find()) {
            return "DILUTION";
        }
        return lossSide(title) ? "LOSS" : null;
    }

    /**
     * <b>매도 트리거로 쓸 수 있는</b> 리스크만 — 현재는 {@code DILUTION} 뿐이다(#877).
     * 해당 없으면 null.
     *
     * <p><b>왜 매수 차단과 분리하는가</b>: {@link #riskFlagOf} 는 매수 차단용이라 오탐이
     * 기회비용으로 끝난다. 그러나 <b>매도 트리거(#872 RISK_EVENT)의 오탐은 멀쩡한
     * 포지션의 실현손실</b>이다. 그래서 매도 쪽은 정밀도가 검증된 것만 쓴다.
     *
     * <p><b>라이브 7,655건(종목코드 타겟) 전수 검증 결과</b>
     * <ul>
     *   <li>{@code DILUTION} 101건 — <b>오탐 0~2건</b>. {@code "X사, N억원 제3자배정 유상증자"}
     *       형태의 정형 공시 어휘라 제목의 주어가 곧 대상 종목이다. → <b>매도 트리거로 채택</b></li>
     *   <li>{@code DELISTING} 10건 — <b>오탐 5건</b>. 제목 정규식은 "누구의 리스크인지"를
     *       구분하지 못한다: {@code "파산 위기 '동전주' 하이닉스, 25년만에 증시 새 황제 등극"}
     *       (과거 회고), {@code "홈플러스 회생절차 폐지에…'반사이익' 롯데쇼핑·이마트 강세"}
     *       (대상엔 호재), {@code "SK, SK시그넷 상장폐지·매각한다"}(SK는 처분하는 쪽),
     *       {@code "靑 … 삼전닉스 레버리지 상장폐지 어려워"}(ETF 정책 논의). → 제외</li>
     *   <li>{@code LOSS} 15건 — <b>오탐 4~5건</b>. 제목의 '적자' 는 편집적 표현이라 정밀도가 낮다:
     *       {@code "'3년간 3.5조 적자' 한화오션, 올 2조 이익 눈앞"}(흑자 전환 기사),
     *       {@code "SK하닉 … '적자 때 임금 조정' 발칵"}(임금협상),
     *       {@code "LG화학 … 진에어는 적자 지속"}(타 종목 교차오염). → 제외</li>
     *   <li>{@code GOVERNANCE} 1건 — 표본이 1건이라 정밀도를 판단할 수 없다. → 제외(사례 축적 후 재검토)</li>
     * </ul>
     *
     * <p>또 투자원칙 §4 에는 '적자' 매도 규칙이 없다. 하방은 -10% 손절이 지킨다. 이 세션에서
     * 원칙에 없는 매도 규칙(NEWS_FADED)이 회전율로 가치를 파괴한 것을 확인했으므로(#871),
     * 노이즈 섞인 제목 기반 매도 트리거를 넣는 것은 같은 실수의 반복이다.
     *
     * <p>제외된 유형도 <b>매수는 계속 차단</b>한다({@link #riskFlagOf}) — 그쪽 오탐은 기회비용뿐이다.
     * 그리고 LLM 이 {@code riskFlag} 를 채워준 경우에는 그 값이 매도를 유발한다(#872) —
     * 이 메서드는 "제목만으로 매도해도 되는가"에만 답한다.
     */
    public static String announcedRiskOf(String title) {
        return "DILUTION".equals(riskFlagOf(title)) ? "DILUTION" : null;
    }
}
