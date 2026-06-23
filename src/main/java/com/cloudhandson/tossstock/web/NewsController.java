package com.cloudhandson.tossstock.web;

import com.cloudhandson.tossstock.news.NewsIngestService;
import com.cloudhandson.tossstock.news.StockNews;
import com.cloudhandson.tossstock.news.StockNewsMapper;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 뉴스 S1-S5 조회/수집. key = 005930 | 반도체 | MARKET (없으면 전체 활성). */
@RestController
@RequestMapping("/api/news")
public class NewsController {

    private final StockNewsMapper mapper;
    private final NewsIngestService ingest;

    public NewsController(StockNewsMapper mapper, NewsIngestService ingest) {
        this.mapper = mapper;
        this.ingest = ingest;
    }

    @GetMapping
    public List<StockNews> list(@RequestParam(required = false) String key,
                                @RequestParam(defaultValue = "100") int limit) {
        String k = (key == null || key.isBlank()) ? null : key.trim();
        return mapper.active(k, Math.min(limit, 300));
    }

    @PostMapping("/ingest")
    public ResponseEntity<NewsIngestService.IngestResult> ingestNow() {
        ingest.ingestAsync();
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(new NewsIngestService.IngestResult(0, 0, 0));
    }
}
