package com.cloudhandson.tossstock.rangetrade;

import com.cloudhandson.tossstock.news.NewsSignals;
import com.cloudhandson.tossstock.news.StockNews;
import com.cloudhandson.tossstock.news.StockNewsMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 강한 악재 배제 게이트 — 활성 뉴스 중 해당 종목 타겟 레벨이 S1(강한 악재)/S2(약한 악재)면 true.
 * #808 NewsFadeDetector 와 같은 패턴의 반대 극성(그쪽은 "호재 소멸", 여기는 "악재 존재").
 * 설계: docs/design/818-range-trade-swing/README.md §7, §9
 */
@Component
public class BadNewsGate {

    private static final Logger log = LoggerFactory.getLogger(BadNewsGate.class);
    private static final int BAD_LEVEL_MAX = 2; // S1/S2 = 악재
    private static final int LOOKUP_LIMIT = 20;

    private final StockNewsMapper newsMapper;

    public BadNewsGate(StockNewsMapper newsMapper) {
        this.newsMapper = newsMapper;
    }

    /**
     * true = 강한 악재 존재(신규 매수 차단 / 보유 중이면 즉시 매도).
     * 조회 실패 시 false(차단하지 않음) — README §9 결정: API 일시 장애로 영원히 매수 못 하거나,
     * 반대로 조회 실패를 악재로 오인해 보유 포지션을 전량 매도하는 쪽이 더 위험하다고 판단.
     * 가격 기반 손절(RANGE_BREAKDOWN)은 뉴스 조회와 무관하게 계속 작동하므로 무방비는 아님.
     */
    public boolean hasStrongBadNews(String symbol) {
        List<StockNews> active;
        try {
            active = newsMapper.active(symbol, LOOKUP_LIMIT);
        } catch (RuntimeException e) {
            log.warn("악재 뉴스 조회 실패(symbol={}) — 차단하지 않음(fail-open): {}", symbol, e.toString());
            return false;
        }
        for (StockNews n : active) {
            String level = NewsSignals.levelOf(n.getSentiment(), symbol);
            if (level != null && Integer.parseInt(level.substring(1)) <= BAD_LEVEL_MAX) {
                return true;
            }
        }
        return false;
    }
}
