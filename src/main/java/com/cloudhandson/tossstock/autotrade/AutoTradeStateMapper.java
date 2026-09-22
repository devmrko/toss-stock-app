package com.cloudhandson.tossstock.autotrade;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;

@Mapper
public interface AutoTradeStateMapper {
    AutoTradeState find();

    int tripCircuitBreaker(@Param("at") LocalDateTime at);

    int setDryRun(@Param("dryRun") boolean dryRun);
}
