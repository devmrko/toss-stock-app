package com.cloudhandson.tossstock.market;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/** 탑50 교체 저장(트랜잭션) — 성공 시에만 기존 결과를 대체. */
@Component
public class VolumeRankWriter {

    private final VolumeRankMapper mapper;

    public VolumeRankWriter(VolumeRankMapper mapper) {
        this.mapper = mapper;
    }

    @Transactional
    public void replaceAll(List<VolumeRank> rows, LocalDateTime asOf) {
        mapper.deleteAll();
        for (VolumeRank r : rows) {
            r.setAsOf(asOf);
            mapper.insert(r);
        }
    }
}
