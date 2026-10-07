package com.cloudhandson.tossstock.rangetrade;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/** range_trade_order_log 접근(감사로그). */
@Mapper
public interface RangeTradeOrderLogMapper {
    int insert(RangeTradeOrderLog log);

    /** 최근 주문로그(성공/실패 모두, 최신순) — 운영 대시보드(#848)용. */
    List<RangeTradeOrderLog> findRecent(@Param("limit") int limit);
}
