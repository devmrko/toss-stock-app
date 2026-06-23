package com.cloudhandson.tossstock.web;

import com.cloudhandson.tossstock.watchlist.Watchlist;
import com.cloudhandson.tossstock.watchlist.WatchlistMapper;
import com.cloudhandson.tossstock.watchlist.WatchlistQuote;
import com.cloudhandson.tossstock.watchlist.WatchlistQuoteService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/** 워치리스트 CRUD + 거래량 정렬 모니터링 집계(watchlist.html 백엔드). */
@RestController
@RequestMapping("/api/watchlist")
public class WatchlistController {

    private final WatchlistMapper mapper;
    private final WatchlistQuoteService quoteService;
    private final com.cloudhandson.tossstock.market.DailyCollector collector;

    public WatchlistController(WatchlistMapper mapper, WatchlistQuoteService quoteService,
                              com.cloudhandson.tossstock.market.DailyCollector collector) {
        this.mapper = mapper;
        this.quoteService = quoteService;
        this.collector = collector;
    }

    /** 원본 행(스모크/디버그용). */
    @GetMapping
    public List<Watchlist> list() {
        return mapper.findAll();
    }

    /** 거래량 내림차순 모니터링 행(상위 50). */
    @GetMapping("/quotes")
    public List<WatchlistQuote> quotes() {
        return quoteService.assemble();
    }

    @PostMapping
    public ResponseEntity<Watchlist> add(@RequestBody AddRequest req) {
        if (req == null || req.symbol() == null || req.symbol().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "symbol 은 필수입니다");
        }
        String symbol = req.symbol().trim();
        if (mapper.findBySymbol(symbol) != null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "이미 워치리스트에 있는 종목입니다: " + symbol);
        }
        Watchlist w = new Watchlist();
        w.setSymbol(symbol);
        w.setMemo(req.memo());
        mapper.insert(w);
        // 등록 시 최근 일봉 백필(지표 즉시 표시)
        collector.backfillSymbol(symbol, null);
        return ResponseEntity.status(HttpStatus.CREATED).body(w);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        mapper.deleteById(id);
        return ResponseEntity.noContent().build();
    }

    public record AddRequest(String symbol, String memo) {
    }
}
