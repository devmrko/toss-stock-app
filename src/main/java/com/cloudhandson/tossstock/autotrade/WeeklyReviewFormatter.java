package com.cloudhandson.tossstock.autotrade;

import com.cloudhandson.tossstock.market.SectorReturn;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;

/**
 * 원칙 §6 "주간 운영 루틴" 리포트 문자열 조립(순수 함수, #874).
 * 설계: docs/design/874-weekly-review-report/README.md §2
 *
 * <p>원칙 §0 은 "주 1회 <b>점검</b>·거래"다. 거래는 이미 룰 기반인데 점검은 구현이 없었다.
 * 거래 주기를 주 1회로 늘리면 §0 의 첫 항목인 -10% 손절 감시가 깨지므로(분 단위 필요),
 * 주기는 유지하고 <b>점검 쪽</b>을 자동화한다.
 *
 * <p>데이터가 없는 항목(§6-3 이벤트 캘린더)은 추정해 채우지 않고 "수동 확인"으로 남긴다.
 */
public final class WeeklyReviewFormatter {

    /** 상대강도 Top/Bottom 각 몇 개를 보일지(§6-1 "Top/Bottom 5"). */
    private static final int RANK_SIZE = 5;

    /** 보유 종목 1줄 — 손절선은 봇이 실제 판정에 쓰는 값과 같아야 한다(설계 §2.2). */
    public record HoldingLine(String symbol, String name, BigDecimal entryPrice,
                               BigDecimal currentPrice, BigDecimal hardFloor,
                               BigDecimal trailFloor, double trailPct) {
    }

    /** 주간 매매 집계(§6-6 "트레이드 로그 3줄 요약"). */
    public record WeekSummary(int trades, BigDecimal grossPnl, BigDecimal fees, BigDecimal netPnl) {
    }

    private WeeklyReviewFormatter() {
    }

    public static String format(LocalDate weekStart, LocalDate weekEnd, WeekSummary week,
                                 List<HoldingLine> holdings, List<SectorReturn> sectors,
                                 int windowDays) {
        StringBuilder sb = new StringBuilder();
        sb.append("📋 **주간 점검**(원칙 §6) — ")
                .append(weekStart).append(" ~ ").append(weekEnd).append('\n');

        sb.append("\n**1) 이번 주 매매**(§6-6)\n");
        if (week == null || week.trades() == 0) {
            sb.append("거래 없음\n");
        } else {
            sb.append("매매 ").append(week.trades()).append("건 · 실현손익 ")
                    .append(won(week.netPnl())).append("원")
                    .append(" (가격차 ").append(won(week.grossPnl()))
                    .append(" / 수수료·세금 ").append(won(week.fees())).append(")\n");
        }

        sb.append("\n**2) 보유 종목 손절선**(§6-4)\n");
        if (holdings == null || holdings.isEmpty()) {
            sb.append("보유 없음\n");
        } else {
            for (HoldingLine h : holdings) {
                sb.append("· `").append(h.symbol()).append("` ")
                        .append(h.name() == null ? "" : h.name())
                        .append(" 진입 ").append(won(h.entryPrice()))
                        .append(" → 현재 ").append(won(h.currentPrice()))
                        .append(" (").append(pct(h.entryPrice(), h.currentPrice())).append(")")
                        .append("\n   하드손절 ").append(won(h.hardFloor()))
                        .append(" / 추적손절 ").append(won(h.trailFloor()))
                        .append("(피크 -").append(trim(h.trailPct())).append("%)\n");
            }
        }

        // 동일가중 평균임을 명시한다 — 시총가중 지수 수익률로 오독되면 판단이 틀어진다.
        // 실측(2026-10-08): KODEX200 은 -4.25% 인데 반도체 동일가중 평균은 +19.77%(224종목)
        // 였다. 둘 다 맞지만 다른 것을 재는 값이다(대형주 약세 + 소형주 강세).
        sb.append("\n**3) 섹터 상대강도 ").append(windowDays).append("일**(§6-1, 동일가중 평균)\n");
        if (sectors == null || sectors.isEmpty()) {
            sb.append("데이터 부족\n");
        } else {
            sb.append("강세: ").append(rank(sectors, true)).append('\n');
            sb.append("약세: ").append(rank(sectors, false)).append('\n');
        }

        sb.append("\n**4) 이벤트 캘린더**(§6-3) — **수동 확인 필요**")
                .append("(실적·정책·학회·총회 일정 데이터 소스 없음)\n");
        sb.append("\n**5) 룰대로 실행**(§6-5) — 매수/매도는 봇이 룰로 집행합니다. ")
                .append("교체 판단이 필요한 종목은 지수 열위 알림을 참고하세요.\n");
        return sb.toString();
    }

    /** 수익률 내림차순 목록에서 상위(또는 하위) RANK_SIZE 개를 "섹터 +x.xx%" 로. */
    private static String rank(List<SectorReturn> sorted, boolean top) {
        int n = Math.min(RANK_SIZE, sorted.size());
        List<SectorReturn> slice = top
                ? sorted.subList(0, n)
                : sorted.subList(sorted.size() - n, sorted.size()).reversed();
        return String.join(", ", slice.stream()
                .map(s -> s.sector() + " " + signed(s.returnPct()) + "%(" + s.symbols() + "종목)")
                .toList());
    }

    private static String won(BigDecimal v) {
        if (v == null) {
            return "N/A";
        }
        return String.format("%,d", v.setScale(0, RoundingMode.HALF_UP).longValue());
    }

    private static String signed(BigDecimal v) {
        if (v == null) {
            return "N/A";
        }
        String s = v.setScale(2, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
        return v.signum() >= 0 ? "+" + s : s;
    }

    /** from 대비 to 변동률. from 이 0·null 이면 N/A. */
    private static String pct(BigDecimal from, BigDecimal to) {
        if (from == null || to == null || from.signum() <= 0) {
            return "N/A";
        }
        BigDecimal p = to.subtract(from)
                .divide(from, 6, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100));
        return signed(p) + "%";
    }

    private static String trim(double d) {
        return d == Math.rint(d) ? String.valueOf((long) d) : String.valueOf(d);
    }
}
