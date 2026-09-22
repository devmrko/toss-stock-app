package com.cloudhandson.tossstock.autotrade;

import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface AutoTradeCandidateMapper {
    List<AutoTradeCandidate> findActive();

    int insert(AutoTradeCandidate candidate);
}
