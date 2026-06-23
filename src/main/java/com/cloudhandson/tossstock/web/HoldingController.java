package com.cloudhandson.tossstock.web;

import com.cloudhandson.tossstock.holding.Holding;
import com.cloudhandson.tossstock.holding.HoldingMapper;
import com.cloudhandson.tossstock.holding.HoldingService;
import com.cloudhandson.tossstock.holding.HoldingView;
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

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/** 수동 보유 포트폴리오 CRUD + 지표 조회. */
@RestController
@RequestMapping("/api/holdings")
public class HoldingController {

    private final HoldingMapper mapper;
    private final HoldingService service;

    public HoldingController(HoldingMapper mapper, HoldingService service) {
        this.mapper = mapper;
        this.service = service;
    }

    @GetMapping
    public List<HoldingView> list() {
        return service.list();
    }

    @PostMapping
    public ResponseEntity<Holding> add(@RequestBody AddRequest req) {
        if (req == null || req.symbol() == null || req.symbol().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "symbol 은 필수입니다");
        }
        if (req.buyPrice() == null || req.buyPrice().signum() <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "매수가는 0보다 커야 합니다");
        }
        Holding h = new Holding();
        h.setSymbol(req.symbol().trim());
        h.setBuyAt(req.buyAt() != null ? req.buyAt() : LocalDateTime.now());
        h.setBuyPrice(req.buyPrice());
        h.setQuantity(req.quantity());
        h.setStopPct(req.stopPct() != null ? req.stopPct() : 8.0);
        h.setMemo(req.memo());
        mapper.insert(h);
        return ResponseEntity.status(HttpStatus.CREATED).body(h);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        mapper.deleteById(id);
        return ResponseEntity.noContent().build();
    }

    public record AddRequest(String symbol, LocalDateTime buyAt, BigDecimal buyPrice,
                             Long quantity, Double stopPct, String memo) {
    }
}
