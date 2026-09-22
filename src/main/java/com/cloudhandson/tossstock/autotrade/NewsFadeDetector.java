package com.cloudhandson.tossstock.autotrade;

import com.cloudhandson.tossstock.news.NewsSignals;
import com.cloudhandson.tossstock.news.StockNews;
import com.cloudhandson.tossstock.news.StockNewsMapper;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 보유 종목의 "호재 소멸" 판정 — 최근 활성 뉴스 중 해당 종목 타겟의 최강 sentiment 가
 * S4 미만(호재 아님)으로 내려갔으면 소멸로 본다. 설계: docs/design/808-auto-trade-engine/README.md §7
 */
@Component
public class NewsFadeDetector {

    private static final int HOT_LEVEL_MIN = 4; // S4 이상만 "호재"로 인정

    private final StockNewsMapper newsMapper;

    public NewsFadeDetector(StockNewsMapper newsMapper) {
        this.newsMapper = newsMapper;
    }

    /** true = 호재 소멸(매도 검토 대상). 활성 뉴스가 아예 없어도 소멸로 간주(호재 근거가 만료됨). */
    public boolean hasNewsFaded(String symbol) {
        List<StockNews> active = newsMapper.active(symbol, 20);
        int best = 0;
        for (StockNews n : active) {
            String level = NewsSignals.levelOf(n.getSentiment(), symbol);
            if (level != null) {
                best = Math.max(best, Integer.parseInt(level.substring(1)));
            }
        }
        return best < HOT_LEVEL_MIN;
    }
}
