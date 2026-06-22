package com.cloudhandson.tossstock.web;

import com.cloudhandson.tossstock.watchlist.Watchlist;
import com.cloudhandson.tossstock.watchlist.WatchlistMapper;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** MyBatis + HikariCP 동작 확인용 스모크 엔드포인트. */
@RestController
@RequestMapping("/api/watchlist")
public class WatchlistController {

    private final WatchlistMapper mapper;

    public WatchlistController(WatchlistMapper mapper) {
        this.mapper = mapper;
    }

    @GetMapping
    public List<Watchlist> list() {
        return mapper.findAll();
    }
}
