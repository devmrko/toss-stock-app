package com.cloudhandson.tossstock.web;

import com.cloudhandson.tossstock.market.ScanStatus;
import com.cloudhandson.tossstock.market.Top50Service;
import com.cloudhandson.tossstock.market.VolumeRank;
import com.cloudhandson.tossstock.market.VolumeRankMapper;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;

/** 시장 전체 거래량 탑50 조회/갱신. */
@RestController
@RequestMapping("/api/top50")
public class Top50Controller {

    private final Top50Service service;
    private final VolumeRankMapper rankMapper;
    private final ScanStatus status;

    public Top50Controller(Top50Service service, VolumeRankMapper rankMapper, ScanStatus status) {
        this.service = service;
        this.rankMapper = rankMapper;
        this.status = status;
    }

    @GetMapping
    public Top50Response get() {
        return new Top50Response(
                status.getState().name(),
                status.getScanned(),
                status.getTotal(),
                rankMapper.latestAsOf(),
                rankMapper.findLatest());
    }

    @PostMapping("/refresh")
    public ResponseEntity<Top50Response> refresh() {
        service.refresh();
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(get());
    }

    public record Top50Response(String state, int scanned, int total,
                                LocalDateTime asOf, List<VolumeRank> rows) {
    }
}
