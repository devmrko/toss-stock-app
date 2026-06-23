package com.cloudhandson.tossstock.web;

import com.cloudhandson.tossstock.market.MarketMetrics.Metrics;
import com.cloudhandson.tossstock.market.MetricsService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** 종목별 일봉 지표(흐름/추세/딥/스윙/거래대금). symbols=콤마구분. */
@RestController
@RequestMapping("/api/metrics")
public class MetricsController {

    private final MetricsService service;

    public MetricsController(MetricsService service) {
        this.service = service;
    }

    @GetMapping
    public Map<String, Metrics> metrics(@RequestParam(required = false) String symbols) {
        if (symbols == null || symbols.isBlank()) {
            return Map.of();
        }
        List<String> list = List.of(symbols.split(",")).stream().map(String::trim).filter(s -> !s.isEmpty()).toList();
        return service.compute(list);
    }
}
