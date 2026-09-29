package com.cloudhandson.tossstock.autotrade;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** 운영 확인용 얇은 어댑터. 설계: docs/design/808-auto-trade-engine/README.md §3 인수조건. */
@RestController
@RequestMapping("/api/autotrade")
public class AutoTradeController {

    private final AutoTradeScheduler scheduler;
    private final CandidateDiscoveryService discoveryService;
    private final AutoTradeStateMapper stateMapper;
    private final AutoTradePositionMapper positionMapper;
    private final AutoTradeCandidateMapper candidateMapper;

    public AutoTradeController(AutoTradeScheduler scheduler, CandidateDiscoveryService discoveryService,
                                AutoTradeStateMapper stateMapper, AutoTradePositionMapper positionMapper,
                                AutoTradeCandidateMapper candidateMapper) {
        this.scheduler = scheduler;
        this.discoveryService = discoveryService;
        this.stateMapper = stateMapper;
        this.positionMapper = positionMapper;
        this.candidateMapper = candidateMapper;
    }

    /** 스케줄러 강제 1회 실행(드라이런 여부는 auto_trade_state/설정을 그대로 따름 — 이 호출 자체가 안전장치를 우회하지 않음). */
    @PostMapping("/tick")
    public ResponseEntity<Map<String, Object>> tick() {
        scheduler.tick();
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(status());
    }

    /** 후보 자동등록/자동해제 강제 1회 실행(운영 확인용). */
    @PostMapping("/discover")
    public ResponseEntity<Map<String, Object>> discover() {
        discoveryService.refresh();
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(status());
    }

    @GetMapping("/status")
    public Map<String, Object> status() {
        AutoTradeState state = stateMapper.find();
        List<AutoTradePosition> holdings = positionMapper.findHolding();
        List<AutoTradeCandidate> candidates = candidateMapper.findActive();
        return Map.of(
                "dryRun", state == null || state.isDryRun(),
                "circuitBreakerTripped", state != null && state.isCircuitBreakerTripped(),
                "totalBudget", state == null ? null : state.getTotalBudget(),
                "holdingCount", holdings.size(),
                "holdings", holdings,
                "activeCandidates", candidates);
    }
}
