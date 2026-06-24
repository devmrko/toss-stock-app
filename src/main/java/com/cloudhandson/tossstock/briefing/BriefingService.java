package com.cloudhandson.tossstock.briefing;

import com.cloudhandson.tossstock.holding.HoldingService;
import com.cloudhandson.tossstock.holding.PositionView;
import com.cloudhandson.tossstock.news.StockNews;
import com.cloudhandson.tossstock.news.StockNewsMapper;
import com.cloudhandson.tossstock.watchlist.WatchlistQuote;
import com.cloudhandson.tossstock.watchlist.WatchlistQuoteService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 장 시작/마감 Discord 브리핑(평일). 웹훅 미설정 시 스킵. */
@Service
public class BriefingService {

    private static final Logger log = LoggerFactory.getLogger(BriefingService.class);
    private static final int NEWS_SCAN = 100;   // 활성 뉴스 스캔 상한
    private static final int NEWS_SHOW = 6;      // 브리핑에 표시할 보유뉴스 수

    private final BriefingProperties props;
    private final DiscordClient discord;
    private final HoldingService holdingService;
    private final WatchlistQuoteService watchlistService;
    private final StockNewsMapper newsMapper;

    public BriefingService(BriefingProperties props, DiscordClient discord, HoldingService holdingService,
                           WatchlistQuoteService watchlistService, StockNewsMapper newsMapper) {
        this.props = props;
        this.discord = discord;
        this.holdingService = holdingService;
        this.watchlistService = watchlistService;
        this.newsMapper = newsMapper;
    }

    @Scheduled(cron = "${briefing.open-cron:0 0 9 * * MON-FRI}", zone = "Asia/Seoul")
    public void openBriefing() {
        send("장 시작");
    }

    @Scheduled(cron = "${briefing.close-cron:0 30 15 * * MON-FRI}", zone = "Asia/Seoul")
    public void closeBriefing() {
        send("장 마감");
    }

    /** 브리핑 1회 생성·전송. 성공 여부 반환. */
    public boolean send(String label) {
        if (!props.enabled()) {
            log.info("DISCORD_WEBHOOK_URL 미설정 — 브리핑 스킵({})", label);
            return false;
        }
        try {
            List<PositionView> positions = holdingService.list();
            List<WatchlistQuote> watchlist = watchlistService.assemble();
            List<HoldingNews> news = holdingNews(positions);
            String msg = BriefingFormatter.build(label, LocalDateTime.now(), positions, news,
                    watchlist, props.watchlistLimit());
            boolean ok = discord.send(props.webhookUrl(), msg);
            log.info("브리핑 전송({}) → {} (보유 {}, 뉴스 {}, 워치 {})", label, ok ? "성공" : "실패",
                    positions.size(), news.size(), watchlist.size());
            return ok;
        } catch (RuntimeException e) {
            log.warn("브리핑 생성/전송 실패({}): {}", label, e.toString());
            return false;
        }
    }

    /** 보유(청산 제외) 종목을 target 으로 갖는 활성 뉴스(S3 제외) 최신순 N건. */
    private List<HoldingNews> holdingNews(List<PositionView> positions) {
        Map<String, String> nameBySym = new LinkedHashMap<>();
        for (PositionView p : positions) {
            if (p.netQty() == null || p.netQty() > 0) {
                nameBySym.putIfAbsent(p.symbol(), p.name() == null ? p.symbol() : p.name());
            }
        }
        if (nameBySym.isEmpty()) {
            return List.of();
        }
        try {
            List<HoldingNews> out = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            for (StockNews n : newsMapper.active(null, NEWS_SCAN)) {
                if (n.getTargets() == null) {
                    continue;
                }
                String hit = null;
                for (String t : n.getTargets().split(",")) {
                    if (nameBySym.containsKey(t.trim())) {
                        hit = t.trim();
                        break;
                    }
                }
                if (hit == null) {
                    continue;
                }
                String level = levelFor(n.getSentiment(), hit);
                if (level == null || level.equals("S3") || !seen.add(n.getExtId())) {
                    continue;
                }
                out.add(new HoldingNews(nameBySym.get(hit), level, n.getTitle()));
                if (out.size() >= NEWS_SHOW) {
                    break;
                }
            }
            return out;
        } catch (RuntimeException e) {
            log.warn("보유 뉴스 조회 실패: {}", e.toString());
            return List.of();
        }
    }

    /** sentiment CSV("005930:S5,반도체:S3")에서 key 의 S레벨. */
    private static String levelFor(String sentimentCsv, String key) {
        if (sentimentCsv == null) {
            return null;
        }
        for (String pair : sentimentCsv.split(",")) {
            int i = pair.indexOf(':');
            if (i > 0 && pair.substring(0, i).trim().equals(key)) {
                String lv = pair.substring(i + 1).trim();
                return lv.matches("S[1-5]") ? lv : null;
            }
        }
        return null;
    }
}
