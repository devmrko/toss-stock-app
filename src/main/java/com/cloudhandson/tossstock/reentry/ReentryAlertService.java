package com.cloudhandson.tossstock.reentry;

import com.cloudhandson.tossstock.briefing.DiscordClient;
import com.cloudhandson.tossstock.toss.PriceCache;
import com.cloudhandson.tossstock.toss.dto.TossPrice;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/** 추적손절 재진입 알림. 설계: docs/design/reentry-alert/README.md, fn-checkAll.md */
@Service
public class ReentryAlertService {

    private static final Logger log = LoggerFactory.getLogger(ReentryAlertService.class);

    private final ReentryAlertProperties props;
    private final ReentryWatchMapper mapper;
    private final PriceCache priceCache;
    private final DiscordClient discord;

    public ReentryAlertService(ReentryAlertProperties props, ReentryWatchMapper mapper,
                                PriceCache priceCache, DiscordClient discord) {
        this.props = props;
        this.mapper = mapper;
        this.priceCache = priceCache;
        this.discord = discord;
    }

    @Scheduled(cron = "${reentry-alert.cron:0 * 9-15 * * MON-FRI}", zone = "Asia/Seoul")
    public void checkAll() {
        if (!props.enabled()) {
            return;
        }
        List<ReentryWatch> watches = mapper.findActive();
        if (watches.isEmpty()) {
            return;
        }
        for (ReentryWatch w : watches) {
            try {
                checkOne(w);
            } catch (RuntimeException e) {
                log.warn("재진입 감시 실패(symbol={}): {}", w.getSymbol(), e.toString());
            }
        }
    }

    private void checkOne(ReentryWatch w) {
        List<TossPrice> prices = priceCache.get(List.of(w.getSymbol()));
        if (prices.isEmpty() || prices.get(0).lastPrice() == null) {
            return; // 콜드 캐시 — 다음 실행에 재시도
        }
        BigDecimal current = new BigDecimal(prices.get(0).lastPrice());

        ReentryStage stage = ReentryStageCalculator.decide(
                current, w.getReferencePrice(), w.getStage1Pct(), w.getStage2Pct(),
                w.getStage1AlertedAt() != null, w.getStage2AlertedAt() != null);

        if (stage == ReentryStage.NONE) {
            return;
        }
        double pct = current.subtract(w.getReferencePrice())
                .divide(w.getReferencePrice(), java.math.MathContext.DECIMAL64)
                .doubleValue() * 100;
        String msg = format(stage, w.getSymbol(), current, w.getReferencePrice(), pct);
        if (!discord.send(props.webhookUrl(), msg)) {
            return; // 전송 실패 — 상태 갱신 안 함, 다음 실행 재시도
        }
        LocalDateTime now = LocalDateTime.now();
        if (stage == ReentryStage.STAGE2) {
            mapper.markStage2(w.getId(), now);
        } else {
            mapper.markStage1(w.getId(), now);
        }
        log.info("재진입 알림 전송({}, {}) 현재가={} 기준가={} 상승률={}%", w.getSymbol(), stage,
                current, w.getReferencePrice(), String.format("%.2f", pct));
    }

    private static String format(ReentryStage stage, String symbol, BigDecimal current,
                                  BigDecimal reference, double pct) {
        String pctStr = String.format("%.2f", pct);
        if (stage == ReentryStage.STAGE2) {
            return "🟢 " + symbol + " 재진입 확인 기준 도달 — 현재가 " + current + "원 (기준가 "
                    + reference + "원 대비 +" + pctStr + "%). 원칙 §4의 표준 추적손절 대칭 기준(+"
                    + "10%)을 충족했습니다. 재진입 검토 시 새 매수가 기준으로 손절·추적손절선을 다시 설정하세요.";
        }
        return "🟡 " + symbol + " 초기 회복 신호 — 현재가 " + current + "원 (기준가 "
                + reference + "원 대비 +" + pctStr + "%). 원칙 §4: 아직 확정 신호는 아니지만, 소량 재진입을 "
                + "검토해볼 수 있는 구간입니다. 물타기 금지 — 반드시 가격 확인 후 소량만.";
    }
}
