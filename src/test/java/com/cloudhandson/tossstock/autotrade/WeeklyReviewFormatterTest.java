package com.cloudhandson.tossstock.autotrade;

import com.cloudhandson.tossstock.market.SectorReturn;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #874 원칙 §6 주간 점검 리포트. 설계: docs/design/874-weekly-review-report/README.md §5
 */
class WeeklyReviewFormatterTest {

    private static final LocalDate START = LocalDate.of(2026, 10, 1);
    private static final LocalDate END = LocalDate.of(2026, 10, 8);

    private static SectorReturn sector(String name, String pct, int n) {
        return new SectorReturn(name, new BigDecimal(pct), n);
    }

    /** 수익률 내림차순(쿼리가 ORDER BY 2 DESC 로 주는 순서). */
    private static List<SectorReturn> sectors() {
        return List.of(
                sector("반도체", "8.21", 40), sector("방산", "6.10", 12),
                sector("조선", "4.55", 9), sector("2차전지", "2.30", 25),
                sector("바이오", "1.05", 60), sector("금융", "-0.40", 18),
                sector("건설", "-3.12", 22), sector("화장품", "-5.60", 14),
                sector("엔터", "-7.80", 8), sector("유통소비재", "-9.25", 30));
    }

    private static WeeklyReviewFormatter.HoldingLine holding() {
        return new WeeklyReviewFormatter.HoldingLine("005930", "삼성전자",
                BigDecimal.valueOf(200_000), BigDecimal.valueOf(225_000),
                BigDecimal.valueOf(180_000), BigDecimal.valueOf(211_500), 10.0);
    }

    @Test
    void 모든_섹션이_포함되고_숫자가_정확하다() {
        WeeklyReviewFormatter.WeekSummary week = new WeeklyReviewFormatter.WeekSummary(
                8, BigDecimal.valueOf(-153_166), BigDecimal.valueOf(61_323),
                BigDecimal.valueOf(-214_489));

        String s = WeeklyReviewFormatter.format(START, END, week, List.of(holding()), sectors(), 20);

        // §6-6 트레이드 로그
        assertThat(s).contains("매매 8건").contains("-214,489").contains("61,323");
        // §6-4 손절선
        assertThat(s).contains("`005930` 삼성전자")
                .contains("진입 200,000").contains("현재 225,000").contains("+12.5%")
                .contains("하드손절 180,000").contains("추적손절 211,500(피크 -10%)");
        // §6-1 상대강도 — 상위는 내림차순, 하위는 최하위부터
        assertThat(s).contains("강세: 반도체 +8.21%(40종목), 방산 +6.1%(12종목), 조선 +4.55%(9종목), "
                + "2차전지 +2.3%(25종목), 바이오 +1.05%(60종목)");
        assertThat(s).contains("약세: 유통소비재 -9.25%(30종목), 엔터 -7.8%(8종목), 화장품 -5.6%(14종목), "
                + "건설 -3.12%(22종목), 금융 -0.4%(18종목)");
        assertThat(s).contains("상대강도 20일").contains("동일가중 평균");
    }

    @Test
    void 이벤트_캘린더는_수동확인으로_명시된다() {
        // 인수조건 6 — 없는 데이터를 추정해 채우지 않는다.
        String s = WeeklyReviewFormatter.format(START, END, null, List.of(), List.of(), 20);
        assertThat(s).contains("이벤트 캘린더").contains("수동 확인");
    }

    @Test
    void 보유가_없어도_깨지지_않는다() {
        // 인수조건 8
        String s = WeeklyReviewFormatter.format(START, END,
                new WeeklyReviewFormatter.WeekSummary(0, null, null, null),
                List.of(), sectors(), 20);
        assertThat(s).contains("보유 없음").contains("거래 없음");
    }

    @Test
    void 섹터가_5개_미만이면_있는_만큼만_출력한다() {
        List<SectorReturn> few = List.of(sector("반도체", "3.0", 5), sector("조선", "-2.0", 4));
        String s = WeeklyReviewFormatter.format(START, END, null, List.of(), few, 20);
        assertThat(s).contains("강세: 반도체 +3%(5종목), 조선 -2%(4종목)");
        assertThat(s).contains("약세: 조선 -2%(4종목), 반도체 +3%(5종목)");
    }

    @Test
    void 섹터_데이터가_없으면_데이터_부족으로_표기() {
        String s = WeeklyReviewFormatter.format(START, END, null, List.of(), List.of(), 20);
        assertThat(s).contains("데이터 부족");
    }

    @Test
    void 현재가가_없으면_NA로_표기하고_계속한다() {
        // 콜드 캐시 — 리포트가 멈추면 안 된다.
        WeeklyReviewFormatter.HoldingLine h = new WeeklyReviewFormatter.HoldingLine(
                "005930", null, BigDecimal.valueOf(200_000), null,
                BigDecimal.valueOf(180_000), BigDecimal.valueOf(211_500), 10.0);
        String s = WeeklyReviewFormatter.format(START, END, null, List.of(h), List.of(), 20);
        assertThat(s).contains("현재 N/A").contains("(N/A)");
    }

    @Test
    void 멀티배거_완화폭이_그대로_표기된다() {
        // #873 완화(15%)가 리포트에도 반영돼야 점검이 실제 판정과 일치한다.
        WeeklyReviewFormatter.HoldingLine h = new WeeklyReviewFormatter.HoldingLine(
                "005930", "삼성전자", BigDecimal.valueOf(10_000), BigDecimal.valueOf(38_000),
                BigDecimal.valueOf(9_000), BigDecimal.valueOf(34_000), 15.0);
        String s = WeeklyReviewFormatter.format(START, END, null, List.of(h), List.of(), 20);
        assertThat(s).contains("추적손절 34,000(피크 -15%)");
    }
}
