package com.cloudhandson.tossstock.rangetrade;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;

/** range_trade_state 접근(싱글턴 id=1). #808 auto_trade_state 와 별개. */
@Mapper
public interface RangeTradeStateMapper {

    RangeTradeState find();

    int setDryRun(@Param("dryRun") boolean dryRun);

    int tripCircuitBreaker(@Param("at") LocalDateTime at);
}
