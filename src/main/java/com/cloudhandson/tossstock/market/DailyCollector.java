package com.cloudhandson.tossstock.market;

import com.cloudhandson.tossstock.toss.TossApiClient;
import com.cloudhandson.tossstock.toss.TossApiClient.CandlePage;
import com.cloudhandson.tossstock.toss.TossApiException;
import com.cloudhandson.tossstock.toss.dto.TossCandle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 계층1 일봉 수집기: 전체 시장 일봉 OHLCV 백필/갱신 + 거래량 탑50 materialize.
 * 설계: docs/design/419-daily-ohlcv/fn-daily-collector.md · ADR-0006
 */
@Service
public class DailyCollector {

    private static final Logger log = LoggerFactory.getLogger(DailyCollector.class);
    private static final int TOP_N = 50;
    private static final int PAGE = 200;
    private static final int MAX_PAGES = 3;

    private final UniverseMapper universeMapper;
    private final DailyOhlcvMapper dailyMapper;
    private final VolumeRankWriter writer;
    private final TossApiClient toss;
    private final ScanStatus status;

    @Value("${toss.scan.throttle-ms:90}")
    private long throttleMs;
    @Value("${toss.scan.backoff-ms:1200}")
    private long backoffMs;
    @Value("${toss.scan.max-retry:4}")
    private int maxRetry;

    public DailyCollector(UniverseMapper universeMapper, DailyOhlcvMapper dailyMapper,
                          VolumeRankWriter writer, TossApiClient toss, ScanStatus status) {
        this.universeMapper = universeMapper;
        this.dailyMapper = dailyMapper;
        this.writer = writer;
        this.toss = toss;
        this.status = status;
    }

    /** 매 영업일 15:40 KST 당일 일봉 갱신 + 탑50 materialize. */
    @Scheduled(cron = "${toss.scan.cron:0 40 15 * * MON-FRI}", zone = "Asia/Seoul")
    public void scheduledRefresh() {
        refreshLatest();
    }

    /** 전 종목 당일/전일 일봉 upsert 후 탑50 갱신. */
    @Async
    public void refreshLatest() {
        runScan("일봉 갱신", u -> retry(() -> toss.getDailyCandles(u.getSymbol(), 2), u.getSymbol()));
    }

    /** 전 종목 1년 일봉 백필 후 탑50 갱신. */
    @Async
    public void backfillYear() {
        runScan("일봉 1년 백필", u -> fetchYear(u.getSymbol()));
    }

    private void runScan(String label, Function<Universe, List<TossCandle>> fetch) {
        List<Universe> uni = universeMapper.findAll();
        if (!status.startIfIdle(uni.size())) {
            log.info("{} 스킵 — 이미 작업 중", label);
            return;
        }
        long t0 = System.currentTimeMillis();
        try {
            for (Universe u : uni) {
                try {
                    for (TossCandle c : fetch.apply(u)) {
                        DailyOhlcv row = toDaily(u.getSymbol(), c);
                        if (row != null) {
                            dailyMapper.upsert(row);
                        }
                    }
                } catch (RuntimeException e) {
                    log.debug("{} 종목 스킵 {}: {}", label, u.getSymbol(), e.getMessage());
                }
                status.incScanned();
                sleep(throttleMs);
            }
            materializeTop50();
            status.done();
            log.info("{} 완료: {}종목, {}s", label, uni.size(), (System.currentTimeMillis() - t0) / 1000);
        } catch (RuntimeException e) {
            status.error(e.getMessage());
            log.error("{} 실패", label, e);
        }
    }

    /**
     * 1년치 일봉(페이지네이션). before=직전 nextBefore, count<=200.
     * 페이지 단위로 재시도하고, 한 페이지가 실패해도 **그때까지 모은 페이지는 보존**한다
     * (page2 실패가 page1 데이터를 버리지 않도록).
     */
    List<TossCandle> fetchYear(String symbol) {
        LocalDate cutoff = LocalDate.now().minusYears(1);
        List<TossCandle> acc = new ArrayList<>();
        String before = null;
        for (int page = 0; page < MAX_PAGES; page++) {
            CandlePage p = retryPage(symbol, before);
            if (p == null || p.candles().isEmpty()) {
                break;   // 실패/끝 → 지금까지 모은 것 유지
            }
            acc.addAll(p.candles());
            LocalDate oldest = tradeDate(p.candles().get(p.candles().size() - 1));
            if (oldest == null || oldest.isBefore(cutoff) || p.nextBefore() == null || p.nextBefore().isBlank()) {
                break;
            }
            before = p.nextBefore();
        }
        return acc.stream().filter(c -> {
            LocalDate d = tradeDate(c);
            return d != null && !d.isBefore(cutoff);
        }).toList();
    }

    /** 단일 페이지 호출 + 429 백오프 재시도. 포기 시 null(부분결과 보존). */
    private CandlePage retryPage(String symbol, String before) {
        int attempt = 0;
        while (true) {
            try {
                return toss.getDailyCandlePage(symbol, PAGE, before);
            } catch (TossApiException e) {
                if (e.getStatus() == 429 && attempt < maxRetry) {
                    attempt++;
                    sleep(backoffMs * attempt);
                    continue;
                }
                return null;
            } catch (RuntimeException e) {
                return null;
            }
        }
    }

    /** daily_ohlcv 최신일 거래량 탑50 → 섹터/등락률 부여 → VOLUME_RANK 교체저장. */
    void materializeTop50() {
        LocalDate latest = dailyMapper.latestDate();
        if (latest == null) {
            return;
        }
        List<VolumeRank> top = dailyMapper.topByVolumeOnLatest(TOP_N);
        Map<String, BigDecimal> prevClose = dailyMapper.prevCloseBefore(latest).stream()
                .filter(d -> d.getCloseP() != null)
                .collect(Collectors.toMap(DailyOhlcv::getSymbol, DailyOhlcv::getCloseP, (a, b) -> a));
        int rnk = 1;
        for (VolumeRank r : top) {
            r.setRnk(rnk++);
            BigDecimal pc = prevClose.get(r.getSymbol());
            r.setPrevClose(pc);
            r.setChangeRate(changeRate(r.getLastPrice(), pc));
        }
        writer.replaceAll(top, LocalDateTime.now());
    }

    /** 순수: 캔들 → 일봉 행. 파싱 실패 필드는 null. */
    DailyOhlcv toDaily(String symbol, TossCandle c) {
        LocalDate d = tradeDate(c);
        if (d == null) {
            return null;
        }
        return new DailyOhlcv(symbol, d, dec(c.openPrice()), dec(c.highPrice()),
                dec(c.lowPrice()), dec(c.closePrice()), lng(c.volume()));
    }

    static Double changeRate(BigDecimal last, BigDecimal prevClose) {
        if (last == null || prevClose == null || prevClose.signum() == 0) {
            return null;
        }
        return last.subtract(prevClose).divide(prevClose, 6, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100)).setScale(2, RoundingMode.HALF_UP).doubleValue();
    }

    private List<TossCandle> retry(java.util.function.Supplier<List<TossCandle>> call, String symbol) {
        int attempt = 0;
        while (true) {
            try {
                return call.get();
            } catch (TossApiException e) {
                if (e.getStatus() == 429 && attempt < maxRetry) {
                    attempt++;
                    sleep(backoffMs * attempt);
                    continue;
                }
                return List.of();
            } catch (RuntimeException e) {
                return List.of();
            }
        }
    }

    private static LocalDate tradeDate(TossCandle c) {
        try {
            return OffsetDateTime.parse(c.timestamp()).toLocalDate();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static BigDecimal dec(String s) {
        try { return s == null ? null : new BigDecimal(s); }
        catch (NumberFormatException e) { return null; }
    }

    private static Long lng(String s) {
        try { return s == null ? null : new BigDecimal(s).longValue(); }
        catch (NumberFormatException e) { return null; }
    }

    private static void sleep(long ms) {
        if (ms <= 0) return;
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("스캔 중단됨", e);
        }
    }
}
