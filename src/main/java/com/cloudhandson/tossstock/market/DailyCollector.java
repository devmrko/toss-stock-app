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
    @Value("${toss.scan.chunk-days:30}")
    private int chunkDays;      // 후방 백필 1회당 가져올 일수(count<=200)
    @Value("${toss.scan.target-days:365}")
    private int targetDays;     // 목표 과거 깊이(이만큼 과거까지 채우면 종료)

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

    /**
     * 점진적 후방 백필: 현재 가진 가장 오래된 날짜보다 더 과거로 chunkDays 만큼 한 번 가져온다.
     * 매일(스케줄) 호출하면 하루에 한 청크씩 과거로 확장 → 레이트리밋을 일 단위로 분산.
     * 목표 깊이(targetDays) 도달 시 더 가져오지 않음.
     */
    @Async
    @Scheduled(cron = "${toss.scan.backfill-cron:0 10 16 * * MON-FRI}", zone = "Asia/Seoul")
    public void backfillOlderChunk() {
        LocalDate today = LocalDate.now();
        LocalDate target = today.minusDays(targetDays);
        LocalDate min = dailyMapper.minDate();
        if (min != null && !min.isAfter(target)) {
            log.info("후방 백필 목표({}일) 도달 — 더 가져올 과거 없음", targetDays);
            return;
        }
        LocalDate boundary = (min == null) ? today.plusDays(1) : min;  // 이 날짜보다 과거를 가져옴
        // UTC 'Z' 형식 사용: '+09:00' 의 '+' 가 쿼리 파라미터에서 공백으로 깨지는 문제 회피.
        String before = boundary + "T00:00:00.000Z";
        runScan("과거 일봉 청크(before " + boundary + ", " + chunkDays + "일)",
                u -> retry(() -> olderCandles(u.getSymbol(), before, target), u.getSymbol()));
    }

    /**
     * 단일 종목 일봉을 from(구매일/등록일) 이후 현재까지 백필. KR/US 공용.
     * 등록 시 호출 → 그 종목의 지표·보유 고점/MDD 즉시 채움.
     */
    @Async
    public void backfillSymbol(String symbol, LocalDate from) {
        LocalDate cutoff = from != null ? from : LocalDate.now().minusDays(60);
        List<TossCandle> all = new ArrayList<>();
        String before = null;
        for (int page = 0; page < 6; page++) {   // 최대 ~1200거래일
            CandlePage p = retryPage(symbol, before);
            if (p == null || p.candles().isEmpty()) {
                break;
            }
            all.addAll(p.candles());
            LocalDate oldest = tradeDate(p.candles().get(p.candles().size() - 1));
            if (oldest == null || !oldest.isAfter(cutoff) || p.nextBefore() == null || p.nextBefore().isBlank()) {
                break;
            }
            before = p.nextBefore();
        }
        int n = 0;
        for (TossCandle c : all) {
            LocalDate d = tradeDate(c);
            if (d != null && !d.isBefore(cutoff)) {
                DailyOhlcv row = toDaily(symbol, c);
                if (row != null) {
                    dailyMapper.upsert(row);
                    n++;
                }
            }
        }
        log.info("종목 일봉 백필 {}: {} ~ 현재, {}건", symbol, cutoff, n);
    }

    /** boundary 이전 chunkDays 개 일봉 중 target 이후만. */
    private List<TossCandle> olderCandles(String symbol, String before, LocalDate target) {
        CandlePage p = toss.getDailyCandlePage(symbol, chunkDays, before);
        return p.candles().stream().filter(c -> {
            LocalDate d = tradeDate(c);
            return d != null && !d.isBefore(target);
        }).toList();
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
