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

    /** 실제로는 호재인 전환·개선 표현 — LOSS 로 보지 않는다. */
    private static final Pattern LOSS_POSITIVE = Pattern.compile(
            "흑자\\s?전환|적자\\s?탈출|적자\\s?축소|적자\\s?폭\\s?축소|적자\\s?개선|적자\\s?감소");

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
}
