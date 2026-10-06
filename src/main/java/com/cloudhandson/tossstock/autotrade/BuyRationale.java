package com.cloudhandson.tossstock.autotrade;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * #808 매수 결정근거 문자열 생성(순수 함수). 호출부(AutoTradeScheduler.scanCandidates)에서 이미
 * 계산된 판정값만 받아 포맷한다 — 판정도, I/O도 하지 않는다.
 * 예) {@code PER11.6/PBR0.47(저평가) 펀더3/5(실적X재무O배당O유동O강도X) 인기:가격+2.3% 뉴스:S5 "안랩...급등"}
 * 설계: docs/design/832-decision-rationale-logging/README.md §6, §7
 */
public final class BuyRationale {

    /** 뉴스 제목이 길어 message(VARCHAR2(500))를 잡아먹지 않게 자르는 상한. */
    private static final int MAX_NEWS_LEN = 60;
    private static final String NA = "N/A";

    private BuyRationale() {
    }

    /**
     * @param v                  PER/PBR 조회 결과(null·결측 허용)
     * @param cheap              {@link ValuationChecker#isUndervalued} 결과
     * @param rerateCatalyst     {@link CapitalReturnCatalystDetector#hasRecentCatalyst} 결과
     * @param catalystHeadline   재평가 촉매로 본 뉴스 제목(없으면 null)
     * @param score              펀더멘털 5항목 채점 결과(null 허용)
     * @param volumeSpike        거래량 스파이크로 인기 판정됐는지
     * @param volumeRatio        평균 대비 거래량 배수(데이터 부족 시 0)
     * @param priceMove          당일 가격반응으로 인기 판정됐는지
     * @param priceMovePct       당일 가격변동률(%) (데이터 부족 시 0)
     * @param triggerNewsTitle   매수를 트리거한 활성 뉴스 표시문자열(레벨+제목, 없으면 null)
     */
    public static String describe(Valuation v, boolean cheap, boolean rerateCatalyst, String catalystHeadline,
                                   FundamentalScore score, boolean volumeSpike, double volumeRatio,
                                   boolean priceMove, double priceMovePct, String triggerNewsTitle) {
        return valuationPart(v, cheap, rerateCatalyst, catalystHeadline)
                + " " + fundamentalPart(score)
                + " " + popularityPart(volumeSpike, volumeRatio, priceMove, priceMovePct)
                + " " + newsPart(triggerNewsTitle);
    }

    private static String valuationPart(Valuation v, boolean cheap, boolean rerateCatalyst, String catalystHeadline) {
        String per = v == null ? NA : plain(v.per());
        String pbr = v == null ? NA : plain(v.pbr());
        StringBuilder tags = new StringBuilder();
        if (cheap) {
            tags.append("저평가");
        }
        if (rerateCatalyst) {
            if (tags.length() > 0) {
                tags.append(',');
            }
            tags.append("재평가촉매");
            if (catalystHeadline != null && !catalystHeadline.isBlank()) {
                tags.append(":\"").append(clip(catalystHeadline)).append('"');
            }
        }
        String suffix = tags.length() == 0 ? "(통과경로" + NA + ")" : "(" + tags + ")";
        return "PER" + per + "/PBR" + pbr + suffix;
    }

    private static String fundamentalPart(FundamentalScore s) {
        if (s == null) {
            return "펀더" + NA;
        }
        return "펀더" + s.passCount() + "/5("
                + "실적" + ox(s.earningsQuality())
                + "재무" + ox(s.balanceSheet())
                + "배당" + ox(s.capitalReturn())
                + "유동" + ox(s.liquidity())
                + "강도" + ox(s.relativeStrength()) + ")";
    }

    private static String popularityPart(boolean volumeSpike, double volumeRatio,
                                          boolean priceMove, double priceMovePct) {
        StringBuilder sb = new StringBuilder("인기:");
        if (volumeSpike) {
            sb.append("거래량x").append(plain(BigDecimal.valueOf(volumeRatio))).append('배');
        }
        if (priceMove) {
            if (volumeSpike) {
                sb.append(',');
            }
            sb.append("가격").append(pct(priceMovePct));
        }
        if (!volumeSpike && !priceMove) {
            sb.append(NA);
        }
        return sb.toString();
    }

    private static String newsPart(String triggerNewsTitle) {
        if (triggerNewsTitle == null || triggerNewsTitle.isBlank()) {
            return "뉴스:" + NA;
        }
        return "뉴스:" + clip(triggerNewsTitle);
    }

    private static String ox(boolean b) {
        return b ? "O" : "X";
    }

    /** 개행·연속공백을 눌러 message 한 줄 가독성을 지키고, 길이도 제한. */
    private static String clip(String s) {
        String flat = s.replaceAll("\\s+", " ").trim();
        return flat.length() > MAX_NEWS_LEN ? flat.substring(0, MAX_NEWS_LEN) + "…" : flat;
    }

    /** 소수 2자리까지, 불필요한 0은 떼고 평문으로(11.60 → 11.6). null 은 N/A. */
    private static String plain(BigDecimal d) {
        if (d == null) {
            return NA;
        }
        return d.setScale(2, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
    }

    private static String pct(double d) {
        return (d >= 0 ? "+" : "") + plain(BigDecimal.valueOf(d)) + "%";
    }
}
