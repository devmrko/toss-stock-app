package com.cloudhandson.tossstock.web;

import com.cloudhandson.tossstock.market.DailyOhlcvMapper;
import com.cloudhandson.tossstock.news.NewsClassifier;
import com.cloudhandson.tossstock.news.StockNews;
import com.cloudhandson.tossstock.news.StockNewsMapper;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

/** 전체장 요약: 마켓 뉴스 강도(배너) + 상승비율(breadth). */
@RestController
@RequestMapping("/api/market")
public class MarketOverviewController {

    private final StockNewsMapper newsMapper;
    private final DailyOhlcvMapper dailyMapper;

    public MarketOverviewController(StockNewsMapper newsMapper, DailyOhlcvMapper dailyMapper) {
        this.newsMapper = newsMapper;
        this.dailyMapper = dailyMapper;
    }

    @GetMapping("/overview")
    public Map<String, Object> overview() {
        Map<String, Object> out = new HashMap<>();

        // 마켓 뉴스: 활성 MARKET 신호 중 최강 → level + note(제목)
        String level = null, note = null;
        int best = -1;
        for (StockNews n : newsMapper.active("MARKET", 30)) {
            String lv = marketLevel(n.getSentiment());
            int st = NewsClassifier.strength(lv);
            if (lv != null && st > best) {
                best = st;
                level = lv;
                note = n.getTitle();
            }
        }
        out.put("marketLevel", level);
        out.put("marketNote", note);

        // 상승비율
        Map<String, Object> b = dailyMapper.breadth();
        long up = num(b == null ? null : b.get("UP"));
        long total = num(b == null ? null : b.get("TOTAL"));
        out.put("up", up);
        out.put("total", total);
        out.put("breadthPct", total > 0 ? Math.round(up * 100.0 / total) : 0);
        return out;
    }

    private static String marketLevel(String sentiment) {
        if (sentiment == null) {
            return null;
        }
        for (String pair : sentiment.split(",")) {
            int i = pair.indexOf(':');
            if (i > 0 && pair.substring(0, i).trim().equals("MARKET")) {
                String lv = pair.substring(i + 1).trim();
                return lv.matches("S[1-5]") ? lv : null;
            }
        }
        return null;
    }

    private static long num(Object o) {
        return o instanceof Number n ? n.longValue() : 0L;
    }
}
