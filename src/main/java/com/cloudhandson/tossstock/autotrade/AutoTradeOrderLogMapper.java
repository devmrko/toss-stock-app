package com.cloudhandson.tossstock.autotrade;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface AutoTradeOrderLogMapper {
    int insert(AutoTradeOrderLog log);

    /** 최근 주문로그(성공/실패 모두, 최신순) — 운영 대시보드(#848)용. */
    List<AutoTradeOrderLog> findRecent(@Param("limit") int limit);
}
