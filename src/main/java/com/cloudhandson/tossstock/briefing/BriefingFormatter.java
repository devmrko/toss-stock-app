package com.cloudhandson.tossstock.briefing;

import com.cloudhandson.tossstock.holding.PositionView;
import com.cloudhandson.tossstock.watchlist.WatchlistQuote;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/** 보유+워치리스트 → Discord 마크다운 브리핑(순수). 2000자 제한 대응. */
public final class BriefingFormatter {

    private static final int MAX = 1900;
    private static final DateTimeFormatter DT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final String[] DOW = {"월", "화", "수", "목", "금", "토", "일"};

    private BriefingFormatter() {
    }

    public static String build(String label, LocalDateTime now, List<PositionView> positions,
                               List<WatchlistQuote> watchlist, double takePct, int wlLimit) {
        StringBuilder sb = new StringBuilder();
        sb.append("📊 **").append(label).append(" 브리핑** · ")
                .append(now.format(DT)).append(" (").append(DOW[now.getDayOfWeek().getValue() - 1]).append(")\n");

        // 보유(청산=순수량 0 제외)
        List<PositionView> open = positions.stream()
                .filter(p -> p.netQty() == null || p.netQty() > 0).toList();
        long totU = open.stream().filter(p -> p.unrealizedAmount() != null)
                .mapToLong(PositionView::unrealizedAmount).sum();
        long totR = positions.stream().filter(p -> p.realizedAmount() != null)
                .mapToLong(PositionView::realizedAmount).sum();
        boolean hasU = open.stream().anyMatch(p -> p.unrealizedAmount() != null);
        boolean hasR = positions.stream().anyMatch(p -> p.realizedAmount() != null);

        sb.append("\n💼 **보유 ").append(open.size()).append("종목**");
        if (hasU || hasR) {
            sb.append(" — 평가손익 ").append(hasU ? signed(totU) : "-")
                    .append(" · 실현 ").append(hasR ? signed(totR) : "-");
        }
        sb.append('\n');
        if (open.isEmpty()) {
            sb.append("• 없음\n");
        }
        for (PositionView p : open) {
            sb.append("• **").append(p.name() == null ? p.symbol() : p.name()).append("** ")
                    .append(p.netQty() == null ? "?" : num(p.netQty())).append("주 · 평단 ")
                    .append(num(p.avgCost())).append(" → ").append(num(p.currentPrice()))
                    .append(' ').append(pct(p.unrealizedPct())).append('\n');
            BigDecimal tp = p.avgCost() == null ? null
                    : p.avgCost().multiply(BigDecimal.valueOf(1 + takePct / 100.0));
            sb.append("   🎯익절 ").append(num(tp)).append(" (+").append(trim(takePct)).append("%)")
                    .append(" / 🛑손절 ").append(num(p.stopPrice()))
                    .append(" (-").append(trim(p.stopPct() == null ? 0 : p.stopPct())).append("%)")
                    .append(p.belowStop() ? "  ⚠️손절이탈" : "").append('\n');
        }

        // 워치리스트(거래량순 top N)
        sb.append("\n📈 **워치리스트");
        if (!watchlist.isEmpty()) {
            sb.append(" top ").append(Math.min(wlLimit, watchlist.size()));
        }
        sb.append("**\n");
        if (watchlist.isEmpty()) {
            sb.append("• 없음\n");
        }
        int shown = 0;
        for (WatchlistQuote w : watchlist) {
            if (shown >= wlLimit) {
                break;
            }
            String line = "• " + (w.name() == null ? w.symbol() : w.name()) + " "
                    + num(w.lastPrice()) + " " + pct(w.changeRate()) + "\n";
            if (sb.length() + line.length() > MAX) {
                sb.append("• …\n");
                break;
            }
            sb.append(line);
            shown++;
        }

        String out = sb.toString();
        return out.length() > MAX ? out.substring(0, MAX) + "…" : out;
    }

    private static String num(BigDecimal v) {
        return v == null ? "-" : String.format("%,d", v.setScale(0, RoundingMode.HALF_UP).longValue());
    }

    private static String num(Long v) {
        return v == null ? "-" : String.format("%,d", v);
    }

    private static String signed(long v) {
        return (v > 0 ? "+" : "") + String.format("%,d", v) + "원";
    }

    private static String pct(Double v) {
        return v == null ? "-" : String.format("**%s%.2f%%**", v > 0 ? "+" : "", v);
    }

    /** 정수면 정수로, 아니면 소수 1자리. (8.0 → "8", 7.5 → "7.5") */
    private static String trim(double v) {
        return v == Math.rint(v) ? String.valueOf((long) v) : String.valueOf(Math.round(v * 10) / 10.0);
    }
}
