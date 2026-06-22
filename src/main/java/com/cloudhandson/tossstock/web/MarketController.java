package com.cloudhandson.tossstock.web;

import com.cloudhandson.tossstock.toss.TossApiClient;
import com.cloudhandson.tossstock.toss.dto.TossAccount;
import com.cloudhandson.tossstock.toss.dto.TossPrice;
import com.cloudhandson.tossstock.toss.dto.TossStock;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Arrays;
import java.util.List;

/** 토스 시세/종목/계좌 패스스루 (읽기 전용). */
@RestController
@RequestMapping("/api")
public class MarketController {

    private final TossApiClient toss;

    public MarketController(TossApiClient toss) {
        this.toss = toss;
    }

    @GetMapping("/quote")
    public List<TossPrice> quote(@RequestParam String symbols) {
        return toss.getPrices(parseSymbols(symbols));
    }

    @GetMapping("/stock")
    public List<TossStock> stock(@RequestParam String symbols) {
        return toss.getStocks(parseSymbols(symbols));
    }

    @GetMapping("/account")
    public List<TossAccount> account() {
        return toss.getAccounts();
    }

    /** 경계 검증: 콤마 분리, 공백 제거, 빈 입력 거부. */
    private List<String> parseSymbols(String symbols) {
        if (symbols == null || symbols.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "symbols 파라미터가 필요합니다");
        }
        List<String> parsed = Arrays.stream(symbols.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
        if (parsed.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "유효한 symbol 이 없습니다");
        }
        return parsed;
    }
}
