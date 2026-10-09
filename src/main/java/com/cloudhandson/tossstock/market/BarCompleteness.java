package com.cloudhandson.tossstock.market;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 * 일봉이 <b>완성본</b>인지 — 그 거래일 정규장이 이미 끝났는지 판정한다(순수 함수, #885).
 *
 * <p><b>왜 필요한가.</b> {@code DailyCollector.scheduledRefresh} 는 KST 15:40 에 돈다.
 * KR 장 마감(15:30) 직후라 KR 에는 정확한데, 15:40 KST 는 <b>미국 동부 02:40, 그날(ET)
 * 프리마켓</b>이다. Toss 가 "오늘(ET)" 캔들을 주면 프리마켓 몇 분만 담긴 바가 그 ET 날짜로
 * 저장된다. {@code getDailyCandles(symbol, 2)} 로 최근 2개를 받으므로 다음 날 실행 때
 * 전날 바는 완성값으로 덮어써진다 — 그래서 과거 바는 정확하고 <b>최신 바 1개만 항상
 * 쓰레기</b>였다.
 *
 * <p>실측(2026-10-09): US 최신 바 거래량/20일평균 <b>중위 0.1252</b>(60종목 중 41종목이
 * 0.3 미만), 그런데 <b>전일 바는 중위 0.9601</b>. INTC 10-08 은 DB 종가 113.55·거래량
 * 88,533 인데 실제는 107.08·119,637,300 이었다. 그 최신 바가 {@code PopularityChecker} 와
 * {@code PriceExtension} 이 쓰는 바라, US 주문로그가 0행이었다.
 *
 * <p><b>왜 MarketHours 를 쓰지 않는가.</b> 그쪽은 {@code autotrade} 패키지이고
 * ({@code market} 이 의존하면 레이어 방향이 뒤집힌다) US 를 22:30~05:00 KST 고정으로
 * 단순화해 둔다 — 서머타임에 따라 마감이 05:00~06:00 KST 로 변하므로 여기서는 쓸 수 없는
 * 값이다.
 *
 * 설계: docs/design/885-us-partial-bar/fn-sessionClosed.md
 */
public final class BarCompleteness {

    /** KR 정규장 마감. 정기 수집(15:40)이 통과하도록 여유 10분을 둔다. */
    private static final LocalTime KR_CLOSE_KST = LocalTime.of(15, 30);

    /**
     * US 정규장 마감(16:00 ET)의 KST 환산은 서머타임에 따라 05:00(여름)~06:00(겨울)이다.
     * 07:00 은 두 경우를 모두 여유 있게 덮는다.
     *
     * <p>오차의 방향이 비대칭이라 일부러 늦게 인정한다 — 너무 이르게 잡으면 미완성 바를
     * 완성으로 받아들여 잘못된 매수 판정을 낳지만(지금의 버그 그 자체), 너무 늦게 잡으면
     * 완성 바 저장이 최대 2시간 늦어질 뿐이다. US 장중에는 어차피 전일 완성 바로 판정하는
     * 것이 맞고, 그건 KR 이 장중에 전일 바를 쓰는 것과 같은 구조다.
     */
    private static final LocalTime US_CLOSE_KST_NEXT_DAY = LocalTime.of(7, 0);

    private BarCompleteness() {
    }

    /**
     * {@code tradeDate} 의 정규장이 {@code kstNow} 시점에 이미 끝났는지.
     *
     * @param market    KR / US. 그 외·null 은 US 규칙(더 보수적)
     * @param tradeDate 캔들의 거래일. US 는 ET 날짜다
     * @param kstNow    현재 시각(KST). 호출부가 주입한다 — 이 함수는 시계를 읽지 않는다
     * @return 완성본이면 true. <b>판정 불가(null 입력)는 false</b> — 모르면 저장하지 않는다
     */
    public static boolean sessionClosed(String market, LocalDate tradeDate, LocalDateTime kstNow) {
        if (tradeDate == null || kstNow == null) {
            return false;
        }
        if (isUs(market)) {
            return !kstNow.isBefore(tradeDate.plusDays(1).atTime(US_CLOSE_KST_NEXT_DAY));
        }
        return !kstNow.isBefore(tradeDate.atTime(KR_CLOSE_KST));
    }

    /** 종목코드 → 시장. 6자리 숫자면 KR, 그 외는 US. null 은 US(보수적). */
    public static String marketOf(String symbol) {
        return symbol != null && symbol.matches("\\d{6}") ? "KR" : "US";
    }

    private static boolean isUs(String market) {
        return !"KR".equalsIgnoreCase(market);
    }
}
