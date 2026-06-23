package com.cloudhandson.tossstock.web;

import com.cloudhandson.tossstock.search.SearchHit;
import com.cloudhandson.tossstock.search.SearchMapper;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 종목 검색(이름/코드, 한국+미국). */
@RestController
@RequestMapping("/api/search")
public class SearchController {

    private final SearchMapper mapper;

    public SearchController(SearchMapper mapper) {
        this.mapper = mapper;
    }

    @GetMapping
    public List<SearchHit> search(@RequestParam(required = false) String q,
                                  @RequestParam(defaultValue = "20") int limit) {
        if (q == null || q.trim().length() < 1) {
            return List.of();
        }
        return mapper.search(q.trim(), Math.min(limit, 50));
    }
}
