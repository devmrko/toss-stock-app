package com.cloudhandson.tossstock.autotrade;

import com.cloudhandson.tossstock.toss.TossApiClient;
import com.cloudhandson.tossstock.toss.dto.TossCommission;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 수수료 요율 1일 캐시(#863). 설계: docs/design/863-dynamic-commission-rate/README.md
 *
 * <p>요율을 코드 상수로 들고 있으면 바뀌는 순간 조용히 틀려지고, 그 틀림은 실현손익
 * 집계에서 뒤늦게 드러난다. 2026-10-08 에 누적 실현손익을 토스 숫자와 맞추느라 수수료
 * 귀속 버그를 다섯 번에 걸쳐 잡았다 — 같은 비용을 또 치르지 않으려고 읽어서 쓴다.
 *
 * <p>#847 에서 "매 주문마다 조회"는 레이트리밋 때문에 기각했다. 요율은 연 단위로도 거의
 * 바뀌지 않으므로 1일 캐시면 충분하다.
 */
@Component
public class CommissionRateCache {

    private static final Logger log = LoggerFactory.getLogger(CommissionRateCache.class);

    /** 요율 정상 범위 — 0 초과 1% 미만. 벗어나면 조회값을 버리고 기본값으로 폴백한다. */
    private static final BigDecimal MAX_SANE_RATE = new BigDecimal("0.01");

    private final TossApiClient toss;

    private LocalDate cachedOn;
    private Map<String, BigDecimal> cached = Map.of();

    public CommissionRateCache(TossApiClient toss) {
        this.toss = toss;
    }

    /**
     * 해당 시장의 수수료율. 조회 실패·부재·비정상값이면 하드코딩 기본값으로 폴백한다.
     *
     * <p><b>예외를 밖으로 던지지 않는다</b> — 수수료는 기록·집계용이고 주문의 전제가
     * 아니다. 요율을 못 구해서 주문이 막히면 손절이 멈춘다.
     */
    public synchronized BigDecimal rateFor(String market) {
        LocalDate today = LocalDate.now();
        if (!today.equals(cachedOn)) {
            cached = load(today);
            cachedOn = today;   // 실패해도 당일 재시도하지 않는다(레이트리밋·지연 방지)
        }
        BigDecimal rate = cached.get(key(market));
        return rate != null ? rate : TradingFeeCalculator.defaultCommissionRate(market);
    }

    private Map<String, BigDecimal> load(LocalDate today) {
        try {
            List<TossCommission> rows = toss.getCommissions();
            Map<String, BigDecimal> out = new HashMap<>();
            for (TossCommission c : rows) {
                BigDecimal rate = pick(c);
                if (rate == null) {
                    continue;
                }
                out.put(key(c.marketCountry()), rate);
                warnIfExpired(c, today);
            }
            log.info("수수료 요율 조회: {}", out);
            return out;
        } catch (RuntimeException e) {
            log.warn("수수료 요율 조회 실패 — 기본값 사용: {}", e.toString());
            return Map.of();
        }
    }

    /**
     * 요율표 1행 → 쓸 수 있는 요율. 쓸 수 없으면 null(호출부가 기본값으로 폴백).
     * 범위 검증이 핵심이다 — 0·음수·과도한 값이 그대로 들어오면 수수료가 망가진다.
     */
    static BigDecimal pick(TossCommission c) {
        if (c == null || c.marketCountry() == null || c.commissionRate() == null) {
            return null;
        }
        try {
            BigDecimal rate = new BigDecimal(c.commissionRate().trim());
            if (rate.signum() <= 0 || rate.compareTo(MAX_SANE_RATE) >= 0) {
                log.warn("수수료 요율이 정상 범위를 벗어남 — 무시: {} {}", c.marketCountry(), rate);
                return null;
            }
            return rate;
        } catch (NumberFormatException e) {
            log.warn("수수료 요율 파싱 실패 — 무시: {} '{}'", c.marketCountry(), c.commissionRate());
            return null;
        }
    }

    /**
     * endDate 가 지났어도 조회값을 쓴다 — 롤링 값으로 보이므로(실측: US endDate 가 하루씩
     * 밀린다) 만료가 "이 값이 틀렸다"는 뜻이 아니고, 어차피 상수보다는 최신이다.
     * 다만 요율 체계가 실제로 바뀐 신호일 수도 있어 눈에 띄게 남긴다.
     */
    private static void warnIfExpired(TossCommission c, LocalDate today) {
        if (c.endDate() == null) {
            return;
        }
        try {
            if (LocalDate.parse(c.endDate()).isBefore(today)) {
                log.warn("수수료 요율 endDate 가 지났음(값은 그대로 사용): {} rate={} endDate={}",
                        c.marketCountry(), c.commissionRate(), c.endDate());
            }
        } catch (RuntimeException ignore) {
            // endDate 형식이 달라도 요율 사용을 막지 않는다
        }
    }

    private static String key(String market) {
        return market == null ? "" : market.trim().toUpperCase();
    }
}
