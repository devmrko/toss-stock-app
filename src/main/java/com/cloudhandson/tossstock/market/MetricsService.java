package com.cloudhandson.tossstock.market;

import com.cloudhandson.tossstock.market.MarketMetrics.Metrics;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 종목별 일봉 지표 계산. 추가 토스 호출 없음(저장 일봉). */
@Service
public class MetricsService {

    private static final int LOOKBACK_DAYS = 45;
    private static final int MAX_SYMBOLS = 80;

    private final DailyOhlcvMapper dailyMapper;

    public MetricsService(DailyOhlcvMapper dailyMapper) {
        this.dailyMapper = dailyMapper;
    }

    public Map<String, Metrics> compute(List<String> symbols) {
        Map<String, Metrics> out = new LinkedHashMap<>();
        if (symbols == null || symbols.isEmpty()) {
            return out;
        }
        List<String> capped = symbols.stream().distinct().limit(MAX_SYMBOLS).toList();
        LocalDate from = LocalDate.now().minusDays(LOOKBACK_DAYS);

        // symbol → 오름차순 일봉
        Map<String, List<DailyOhlcv>> bySym = new LinkedHashMap<>();
        for (DailyOhlcv d : dailyMapper.recentForSymbols(capped, from)) {
            bySym.computeIfAbsent(d.getSymbol(), k -> new ArrayList<>()).add(d);
        }
        for (String s : capped) {
            out.put(s, MarketMetrics.of(bySym.getOrDefault(s, List.of()), null));
        }
        return out;
    }
}
