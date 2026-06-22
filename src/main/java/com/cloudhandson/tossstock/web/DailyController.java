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

    /** 1년치 일봉 백그라운드 백필(~26분). */
    @PostMapping("/backfill")
    public ResponseEntity<Map<String, Object>> backfill() {
        collector.backfillYear();
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(status());
    }

    /** 당일 일봉 갱신(~13분). */
    @PostMapping("/refresh")
    public ResponseEntity<Map<String, Object>> refresh() {
        collector.refreshLatest();
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(status());
    }
}
