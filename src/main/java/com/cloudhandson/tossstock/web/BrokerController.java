package com.cloudhandson.tossstock.web;

import com.cloudhandson.tossstock.broker.BrokerMapper;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/** 매매처(증권사) 마스터 — 등록·선택용. (#442) */
@RestController
@RequestMapping("/api/brokers")
public class BrokerController {

    private final BrokerMapper mapper;

    public BrokerController(BrokerMapper mapper) {
        this.mapper = mapper;
    }

    @GetMapping
    public List<String> list() {
        return mapper.findAllNames();
    }

    @PostMapping
    public ResponseEntity<Void> add(@RequestParam String name) {
        String n = name == null ? "" : name.trim();
        if (n.isEmpty() || n.length() > 40) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "name(1~40) 필수");
        }
        mapper.merge(n);
        return ResponseEntity.status(HttpStatus.CREATED).build();
    }
}
