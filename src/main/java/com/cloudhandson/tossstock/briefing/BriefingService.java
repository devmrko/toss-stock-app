package com.cloudhandson.tossstock.briefing;

import com.cloudhandson.tossstock.holding.HoldingService;
import com.cloudhandson.tossstock.holding.PositionView;
import com.cloudhandson.tossstock.watchlist.WatchlistQuote;
import com.cloudhandson.tossstock.watchlist.WatchlistQuoteService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/** 장 시작/마감 Discord 브리핑(평일). 웹훅 미설정 시 스킵. */
@Service
public class BriefingService {

    private static final Logger log = LoggerFactory.getLogger(BriefingService.class);

    private final BriefingProperties props;
    private final DiscordClient discord;
    private final HoldingService holdingService;
    private final WatchlistQuoteService watchlistService;

    public BriefingService(BriefingProperties props, DiscordClient discord,
                           HoldingService holdingService, WatchlistQuoteService watchlistService) {
        this.props = props;
        this.discord = discord;
        this.holdingService = holdingService;
        this.watchlistService = watchlistService;
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
            String msg = BriefingFormatter.build(label, LocalDateTime.now(), positions, watchlist,
                    props.takeProfitPct(), props.watchlistLimit());
            boolean ok = discord.send(props.webhookUrl(), msg);
            log.info("브리핑 전송({}) → {} (보유 {}, 워치 {})", label, ok ? "성공" : "실패",
                    positions.size(), watchlist.size());
            return ok;
        } catch (RuntimeException e) {
            log.warn("브리핑 생성/전송 실패({}): {}", label, e.toString());
            return false;
        }
    }
}
