package com.cloudhandson.tossstock.market;

import com.cloudhandson.tossstock.toss.TossApiClient;
import com.cloudhandson.tossstock.toss.TossApiException;
import com.cloudhandson.tossstock.toss.dto.TossCandle;
import com.cloudhandson.tossstock.toss.dto.TossPrice;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 시장 전체 거래량 탑50 일배치. 설계: docs/design/416-market-top50/fn-scan-batch.md
 */
@Service
public class Top50Service {

    private static final Logger log = LoggerFactory.getLogger(Top50Service.class);
    private static final int TOP_N = 50;
    private static final int PRICE_CHUNK = 20;

    private final UniverseMapper universeMapper;
    private final VolumeRankWriter writer;
    private final TossApiClient toss;
    private final ScanStatus status;

    @Value("${toss.scan.throttle-ms:90}")
    private long throttleMs;
    @Value("${toss.scan.backoff-ms:1200}")
    private long backoffMs;
    @Value("${toss.scan.max-retry:4}")
    private int maxRetry;

    public Top50Service(UniverseMapper universeMapper, VolumeRankWriter writer,
                        TossApiClient toss, ScanStatus status) {
        this.universeMapper = universeMapper;
        this.writer = writer;
        this.toss = toss;
        this.status = status;
    }

    /** 매 영업일 15:40 KST 자동 갱신. */
    @Scheduled(cron = "${toss.scan.cron:0 40 15 * * MON-FRI}", zone = "Asia/Seoul")
    public void scheduledRefresh() {
        log.info("스케줄 거래량 스캔 시작");
        refresh();
    }

    /** 전 종목 거래량 스캔 → 탑50 저장. 중복 실행 방지. */
    @Async
    public void refresh() {
        List<Universe> uni = universeMapper.findAll();
        if (!status.startIfIdle(uni.size())) {
            log.info("이미 스캔 중 — 트리거 무시");
            return;
        }
        long t0 = System.currentTimeMillis();
        try {
            List<SymVol> vols = scanVolumes(uni);
            List<SymVol> top = rankTop50(vols);
            List<VolumeRank> rows = enrich(top);
            writer.replaceAll(rows, LocalDateTime.now());
            status.done();
            log.info("거래량 스캔 완료: {}종목, 탑{} 저장, {}s",
                    uni.size(), rows.size(), (System.currentTimeMillis() - t0) / 1000);
        } catch (RuntimeException e) {
            status.error(e.getMessage());
            log.error("거래량 스캔 실패", e);
        }
    }

    private List<SymVol> scanVolumes(List<Universe> uni) {
        List<SymVol> out = new ArrayList<>(uni.size());
        for (Universe u : uni) {
            out.add(scanOne(u));
            status.incScanned();
            sleep(throttleMs);
        }
        return out;
    }

    private SymVol scanOne(Universe u) {
        int attempt = 0;
        while (true) {
            try {
                List<TossCandle> c = toss.getDailyCandles(u.getSymbol(), 2);
                Long vol = c.isEmpty() ? null : toLong(c.get(0).volume());
                BigDecimal prev = c.size() >= 2 ? toDecimal(c.get(1).closePrice()) : null;
                return new SymVol(u, vol, prev);
            } catch (TossApiException e) {
                if (e.getStatus() == 429 && attempt < maxRetry) {
                    attempt++;
                    sleep(backoffMs * attempt);
                    continue;
                }
                return new SymVol(u, null, null);
            } catch (RuntimeException e) {
                return new SymVol(u, null, null);  // 상폐/정지 등 스킵
            }
        }
    }

    /** 순수: 거래량 desc 상위 50(거래량 null 제외). */
    List<SymVol> rankTop50(List<SymVol> all) {
        return all.stream()
                .filter(s -> s.volume() != null)
                .sorted(Comparator.comparingLong(SymVol::volume).reversed())
                .limit(TOP_N)
                .toList();
    }

    private List<VolumeRank> enrich(List<SymVol> top) {
        List<String> symbols = top.stream().map(s -> s.u().getSymbol()).toList();
        Map<String, TossPrice> priceBySym = fetchPrices(symbols);

        List<VolumeRank> rows = new ArrayList<>();
        int rnk = 1;
        for (SymVol s : top) {
            TossPrice p = priceBySym.get(s.u().getSymbol());
            BigDecimal last = p == null ? null : toDecimal(p.lastPrice());
            VolumeRank r = new VolumeRank();
            r.setRnk(rnk++);
            r.setSymbol(s.u().getSymbol());
            r.setName(s.u().getName());
            r.setMarket(s.u().getMarket());
            r.setVolume(s.volume());
            r.setLastPrice(last);
            r.setPrevClose(s.prevClose());
            r.setChangeRate(changeRate(last, s.prevClose()));
            rows.add(r);
        }
        return rows;
    }

    private Map<String, TossPrice> fetchPrices(List<String> symbols) {
        Map<String, TossPrice> map = new java.util.HashMap<>();
        for (int i = 0; i < symbols.size(); i += PRICE_CHUNK) {
            List<String> chunk = symbols.subList(i, Math.min(i + PRICE_CHUNK, symbols.size()));
            try {
                map.putAll(toss.getPrices(chunk).stream()
                        .collect(Collectors.toMap(TossPrice::symbol, Function.identity(), (a, b) -> a)));
            } catch (RuntimeException e) {
                log.warn("탑50 현재가 조회 실패(chunk {}): {}", i, e.getMessage());
            }
        }
        return map;
    }

    /** 순수: 등락률 % (전일종가 대비), 불가 시 null. */
    static Double changeRate(BigDecimal last, BigDecimal prevClose) {
        if (last == null || prevClose == null || prevClose.signum() == 0) {
            return null;
        }
        return last.subtract(prevClose)
                .divide(prevClose, 6, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100))
                .setScale(2, RoundingMode.HALF_UP)
                .doubleValue();
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

    private static Long toLong(String s) {
        try { return s == null ? null : new BigDecimal(s).longValue(); }
        catch (NumberFormatException e) { return null; }
    }

    private static BigDecimal toDecimal(String s) {
        try { return s == null ? null : new BigDecimal(s); }
        catch (NumberFormatException e) { return null; }
    }

    record SymVol(Universe u, Long volume, BigDecimal prevClose) {
    }
}
