package com.cloudhandson.tossstock.web;

import com.cloudhandson.tossstock.market.DailyCollector;
import com.cloudhandson.tossstock.market.DailyOhlcvMapper;
import com.cloudhandson.tossstock.market.ScanStatus;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.Map;

/** 일봉 수집기 운영 엔드포인트(계층1). */
@RestController
@RequestMapping("/api/daily")
public class DailyController {

    private final DailyCollector collector;
    private final DailyOhlcvMapper dailyMapper;
    private final ScanStatus status;

    public DailyController(DailyCollector collector, DailyOhlcvMapper dailyMapper, ScanStatus status) {
        this.collector = collector;
        this.dailyMapper = dailyMapper;
        this.status = status;
    }

    @GetMapping("/status")
    public Map<String, Object> status() {
        LocalDate latest = dailyMapper.latestDate();
        return Map.of(
                "state", status.getState().name(),
                "scanned", status.getScanned(),
                "total", status.getTotal(),
                "rows", dailyMapper.count(),
                "latestDate", latest == null ? "" : latest.toString());
    }

    /** 과거로 한 청크 더 백필(현재 최오래된 날짜보다 과거 chunkDays 만큼). 매일 호출하면 점진 확장. */
    @PostMapping("/backfill")
    public ResponseEntity<Map<String, Object>> backfill() {
        collector.backfillOlderChunk();
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(status());
    }

    /** 당일 일봉 갱신(~13분). */
    @PostMapping("/refresh")
    public ResponseEntity<Map<String, Object>> refresh() {
        collector.refreshLatest();
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(status());
    }

    /** 단일 종목 일봉 백필(from 이후 현재까지). 등록 시/수동. */
    @PostMapping("/backfill-symbol")
    public ResponseEntity<Map<String, Object>> backfillSymbol(
            @org.springframework.web.bind.annotation.RequestParam String symbol,
            @org.springframework.web.bind.annotation.RequestParam(required = false) String from) {
        java.time.LocalDate f = (from != null && !from.isBlank()) ? java.time.LocalDate.parse(from) : null;
        collector.backfillSymbol(symbol.trim(), f);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(Map.of("symbol", symbol, "from", from == null ? "" : from));
    }
}
