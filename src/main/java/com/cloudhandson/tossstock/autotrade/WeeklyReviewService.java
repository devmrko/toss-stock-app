package com.cloudhandson.tossstock.autotrade;

import com.cloudhandson.tossstock.briefing.DiscordClient;
import com.cloudhandson.tossstock.market.DailyOhlcvMapper;
import com.cloudhandson.tossstock.market.SectorReturn;
import com.cloudhandson.tossstock.market.Universe;
import com.cloudhandson.tossstock.market.UniverseMapper;
import com.cloudhandson.tossstock.toss.PriceCache;
import com.cloudhandson.tossstock.toss.dto.TossPrice;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 원칙 §6 "주간 운영 루틴" 자동 리포트(#874).
 * 설계: docs/design/874-weekly-review-report/README.md
 *
 * <p>원칙 §0 은 "주 1회 <b>점검</b>·거래"다. 분석 결론(설계 §1)은 <b>거래 주기를 바꾸지
 * 않는다</b>는 것이다 — 과잉매매의 원인은 주기가 아니라 NEWS_FADED(전체 청산의 94%)였고
 * #871 이 제거했으며, 주기를 늘리면 §0 의 첫 항목인 -10% 손절 감시(분 단위 필요)가 깨진다.
 * 구현이 없던 쪽은 <b>점검</b>이라 그것만 자동화한다.
 */
@Service
public class WeeklyReviewService {

    private static final Logger log = LoggerFactory.getLogger(WeeklyReviewService.class);

    private final AutoTradeProperties props;
    private final AutoTradePositionMapper positionMapper;
    private final DailyOhlcvMapper dailyMapper;
    private final UniverseMapper universeMapper;
    private final PriceCache priceCache;
    private final DiscordClient discord;

    public WeeklyReviewService(AutoTradeProperties props, AutoTradePositionMapper positionMapper,
                                DailyOhlcvMapper dailyMapper, UniverseMapper universeMapper,
                                PriceCache priceCache, DiscordClient discord) {
        this.props = props;
        this.positionMapper = positionMapper;
        this.dailyMapper = dailyMapper;
        this.universeMapper = universeMapper;
        this.priceCache = priceCache;
        this.discord = discord;
    }

    /** 월요일 장 시작 전 — 점검은 거래 전에 해야 재료가 된다. */
    @Scheduled(cron = "${auto-trade.weekly-review-cron:0 30 8 * * MON}", zone = "Asia/Seoul")
    public void weeklyReview() {
        if (!props.alertsEnabled()) {
            return;
        }
        try {
            LocalDate today = LocalDate.now();
            LocalDate weekStart = today.minusDays(7);
            int window = props.relativeStrengthWindowDays();

            String body = WeeklyReviewFormatter.format(weekStart, today,
                    weekSummary(weekStart),
                    holdingLines(),
                    // 창의 70% 이상 봉이 있는 종목만 — 짧은 구간 수익률이 섞이면 순위가 왜곡된다.
                    dailyMapper.sectorReturns(today.minusDays(window + 10),
                            (int) Math.round(window * 0.7)),
                    window);
            discord.send(props.webhookUrl(), body);
            log.info("주간 점검 리포트 발송({} ~ {})", weekStart, today);
        } catch (RuntimeException e) {
            log.warn("주간 점검 리포트 실패: {}", e.toString());
        }
    }

    /** 최근 7일 실현손익 집계 — 일자별 집계(#851)를 주간으로 합산한다. */
    private WeeklyReviewFormatter.WeekSummary weekSummary(LocalDate weekStart) {
        int trades = 0;
        BigDecimal gross = BigDecimal.ZERO;
        BigDecimal fees = BigDecimal.ZERO;
        BigDecimal net = BigDecimal.ZERO;
        for (DailyRealizedPnl d : positionMapper.dailyRealizedSummary()) {
            if (d.exitDate() == null || d.exitDate().compareTo(weekStart.toString()) < 0) {
                continue;
            }
            trades += d.trades();
            gross = gross.add(nz(d.grossPnl()));
            fees = fees.add(nz(d.fees()));
            net = net.add(nz(d.netPnl()));
        }
        return new WeeklyReviewFormatter.WeekSummary(trades, gross, fees, net);
    }

    /**
     * 보유 종목의 진입가·현재가·손절선. 손절선은 {@link TrailingStopCalculator} 를 직접
     * 호출해 <b>봇이 실제 판정에 쓰는 값</b>과 일치시킨다 — 리포트 숫자가 다르면 점검의
     * 의미가 없다. 추적폭은 #873 의 멀티배거 완화를 반영한다.
     */
    private List<WeeklyReviewFormatter.HoldingLine> holdingLines() {
        List<AutoTradePosition> holdings = positionMapper.findHolding();
        if (holdings.isEmpty()) {
            return List.of();
        }
        Map<String, String> names = namesOf(holdings);
        List<WeeklyReviewFormatter.HoldingLine> out = new ArrayList<>();
        for (AutoTradePosition p : holdings) {
            BigDecimal current = currentPrice(p.getSymbol());
            BigDecimal peak = p.getPeakPrice() == null ? p.getEntryPrice() : p.getPeakPrice();
            double trailPct = TrailingStopCalculator.effectiveTrailPct(peak, p.getEntryPrice(),
                    props.trailStopPct(), props.multibaggerGainPct(), props.multibaggerTrailStopPct());
            out.add(new WeeklyReviewFormatter.HoldingLine(
                    p.getSymbol(), names.get(p.getSymbol()), p.getEntryPrice(), current,
                    TrailingStopCalculator.hardFloor(p.getEntryPrice(), props.hardStopPct()),
                    TrailingStopCalculator.trailFloor(peak, trailPct), trailPct));
        }
        return out;
    }

    private Map<String, String> namesOf(List<AutoTradePosition> holdings) {
        Map<String, String> names = new HashMap<>();
        try {
            for (Universe u : universeMapper.sectorsForSymbols(
                    holdings.stream().map(AutoTradePosition::getSymbol).toList())) {
                names.put(u.getSymbol(), u.getName());
            }
        } catch (RuntimeException e) {
            log.warn("종목명 조회 실패 — 코드만 표기: {}", e.toString());
        }
        return names;
    }

    /** 콜드 캐시면 null — 리포트는 N/A 로 표기하고 계속한다. */
    private BigDecimal currentPrice(String symbol) {
        try {
            List<TossPrice> prices = priceCache.get(List.of(symbol));
            if (prices.isEmpty() || prices.get(0).lastPrice() == null) {
                return null;
            }
            return new BigDecimal(prices.get(0).lastPrice());
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}
