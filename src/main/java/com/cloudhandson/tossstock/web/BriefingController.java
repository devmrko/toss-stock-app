package com.cloudhandson.tossstock.web;

import com.cloudhandson.tossstock.briefing.BriefingService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** Discord 브리핑 수동 트리거(검증용). */
@RestController
@RequestMapping("/api/briefing")
public class BriefingController {

    private final BriefingService service;

    public BriefingController(BriefingService service) {
        this.service = service;
    }

    @PostMapping("/test")
    public ResponseEntity<Map<String, Object>> test(
            @RequestParam(defaultValue = "테스트") String label) {
        boolean ok = service.send(label);
        return ResponseEntity.status(ok ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE)
                .body(Map.of("sent", ok));
    }
}
