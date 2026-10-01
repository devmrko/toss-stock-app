package com.cloudhandson.tossstock.rangetrade;

import org.apache.ibatis.annotations.Mapper;

/** range_trade_order_log 접근(감사로그). */
@Mapper
public interface RangeTradeOrderLogMapper {
    int insert(RangeTradeOrderLog log);
}
