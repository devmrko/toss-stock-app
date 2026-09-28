package com.cloudhandson.tossstock.autotrade;

import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 * 시장별 개장 여부(KST 기준, 순수 함수) — 크론을 시간대별로 쪼개는 대신 틱 내부에서
 * 종목별로 판단. 미국장은 22:30~다음날 05:00(KST)로 자정을 넘겨 요일이 바뀌므로 별도 처리.
 * 설계: docs/design/808-auto-trade-engine/README.md §8(2026-09-28 수정)
 */
public final class MarketHours {

    private static final LocalTime KR_OPEN = LocalTime.of(9, 0);
    private static final LocalTime KR_CLOSE = LocalTime.of(15, 30);
    private static final LocalTime US_OPEN_KST = LocalTime.of(22, 30);
    private static final LocalTime US_CLOSE_KST = LocalTime.of(5, 0);

    private MarketHours() {
    }

    public static boolean isOpen(String market, LocalDateTime kstNow) {
        DayOfWeek day = kstNow.getDayOfWeek();
        LocalTime time = kstNow.toLocalTime();
        if ("US".equalsIgnoreCase(market)) {
            // 저녁 구간(월~금 22:30~24:00)이거나, 새벽 구간(화~토 00:00~05:00 — 전날 미국장의 연장)
            boolean eveningLeg = isWeekday(day) && !time.isBefore(US_OPEN_KST);
            boolean morningLeg = isWeekday(day.minus(1)) && time.isBefore(US_CLOSE_KST);
            return eveningLeg || morningLeg;
        }
        return isWeekday(day) && !time.isBefore(KR_OPEN) && time.isBefore(KR_CLOSE);
    }

    private static boolean isWeekday(DayOfWeek day) {
        return day != DayOfWeek.SATURDAY && day != DayOfWeek.SUNDAY;
    }
}
