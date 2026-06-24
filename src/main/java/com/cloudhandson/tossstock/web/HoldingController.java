package com.cloudhandson.tossstock.web;

import com.cloudhandson.tossstock.holding.Holding;
import com.cloudhandson.tossstock.holding.HoldingMapper;
import com.cloudhandson.tossstock.holding.HoldingService;
import com.cloudhandson.tossstock.holding.PositionView;
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
    private final com.cloudhandson.tossstock.market.DailyCollector collector;

    public HoldingController(HoldingMapper mapper, HoldingService service,
                             com.cloudhandson.tossstock.market.DailyCollector collector) {
        this.mapper = mapper;
        this.service = service;
        this.collector = collector;
    }

    @GetMapping
    public List<PositionView> list() {
        return service.list();
    }

    @PostMapping
    public ResponseEntity<Holding> add(@RequestBody AddRequest req) {
        if (req == null || req.symbol() == null || req.symbol().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "symbol 은 필수입니다");
        }
        if (req.buyPrice() == null || req.buyPrice().signum() <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "가격은 0보다 커야 합니다");
        }
        boolean sell = "SELL".equalsIgnoreCase(req.side());
        if (sell && (req.quantity() == null || req.quantity() <= 0)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "매도는 수량이 필수입니다");
        }
        Holding h = new Holding();
        h.setSymbol(req.symbol().trim());
        h.setSide(sell ? "SELL" : "BUY");
        h.setBuyAt(req.buyAt() != null ? req.buyAt() : LocalDateTime.now());
        h.setBuyPrice(req.buyPrice());
        h.setQuantity(req.quantity());
        h.setStopPct(req.stopPct() != null ? req.stopPct() : 8.0);
        h.setMemo(req.memo());
        h.setBroker(req.broker() != null && !req.broker().isBlank() ? req.broker().trim() : null);
        mapper.insert(h);
        // 매수 등록 시에만 거래일부터 현재까지 일봉 백필(지표·고점/MDD 채움). 매도는 불필요.
        if (!sell) {
            collector.backfillSymbol(h.getSymbol(), h.getBuyAt().toLocalDate());
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(h);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        mapper.deleteById(id);
        return ResponseEntity.noContent().build();
    }

    /** 종목 손절% 수정(그 종목 전 거래 일괄). */
    @org.springframework.web.bind.annotation.PutMapping("/stop")
    public ResponseEntity<Void> updateStop(@org.springframework.web.bind.annotation.RequestParam String symbol,
                                           @org.springframework.web.bind.annotation.RequestParam Double stopPct) {
        if (symbol == null || symbol.isBlank() || stopPct == null || stopPct < 0 || stopPct > 100) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "symbol·stopPct(0~100) 필수");
        }
        mapper.updateStopPctBySymbol(symbol.trim(), stopPct);
        return ResponseEntity.noContent().build();
    }

    /** 거래(투자) 1건 매매처 수정. 빈 값이면 해제(NULL). */
    @org.springframework.web.bind.annotation.PutMapping("/{id}/broker")
    public ResponseEntity<Void> updateBroker(@PathVariable Long id,
                                             @org.springframework.web.bind.annotation.RequestParam(required = false) String broker) {
        String b = (broker == null || broker.isBlank()) ? null : broker.trim();
        mapper.updateBrokerById(id, b);
        return ResponseEntity.noContent().build();
    }

    public record AddRequest(String symbol, String side, String broker, LocalDateTime buyAt, BigDecimal buyPrice,
                             Long quantity, Double stopPct, String memo) {
    }
}
