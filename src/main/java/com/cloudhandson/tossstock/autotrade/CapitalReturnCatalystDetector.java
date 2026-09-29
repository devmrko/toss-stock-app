package com.cloudhandson.tossstock.autotrade;

import com.cloudhandson.tossstock.news.NewsSignals;
import com.cloudhandson.tossstock.news.StockNews;
import com.cloudhandson.tossstock.news.StockNewsMapper;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;

/**
 * 원칙 §2/§3-7 "밸류에이션 재평가 여지" — 이미 싼 것과는 별개로, 자본배분(배당확대·자사주매입·소각)
 * 촉매 뉴스가 있으면 재평가 여지로 인정. ValuationChecker(절대 PER/PBR)와 OR 결합해서 저평가 게이트 완화.
 * 설계: docs/design/808-auto-trade-engine/README.md §2(2026-09-29 수정)
 */
@Component
public class CapitalReturnCatalystDetector {

    private static final List<String> KEYWORDS = List.of(
            "자사주", "소각", "배당", "buyback", "repurchase", "dividend");
    private static final int HOT_LEVEL_MIN = 4;

    private final StockNewsMapper newsMapper;

    public CapitalReturnCatalystDetector(StockNewsMapper newsMapper) {
        this.newsMapper = newsMapper;
    }

    public boolean hasRecentCatalyst(String symbol) {
        List<StockNews> active = newsMapper.active(symbol, 20);
        for (StockNews n : active) {
            if (!"EVENT".equals(n.getKind())) {
                continue;
            }
            String level = NewsSignals.levelOf(n.getSentiment(), symbol);
            if (level == null || Integer.parseInt(level.substring(1)) < HOT_LEVEL_MIN) {
                continue;
            }
            String haystack = (safe(n.getTitle()) + " " + safe(n.getRationale())).toLowerCase(Locale.ROOT);
            if (KEYWORDS.stream().anyMatch(k -> haystack.contains(k.toLowerCase(Locale.ROOT)))) {
                return true;
            }
        }
        return false;
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }
}
