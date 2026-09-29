package com.cloudhandson.tossstock.autotrade;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface AutoTradeCandidateMapper {
    List<AutoTradeCandidate> findActive();

    int insert(AutoTradeCandidate candidate);

    /** 활성 후보 중 해당 심볼이 이미 있는지(중복 등록 방지). */
    boolean existsActive(@Param("symbol") String symbol);

    /** 호재 소멸한 후보 비활성화(자동 정리, #808 2026-09-29). */
    int deactivate(@Param("symbol") String symbol);
}
