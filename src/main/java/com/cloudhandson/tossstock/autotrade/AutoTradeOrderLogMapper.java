package com.cloudhandson.tossstock.autotrade;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface AutoTradeOrderLogMapper {
    int insert(AutoTradeOrderLog log);

    /** 주문로그 페이지(성공/실패 모두, 최신순) — 운영 대시보드(#848/#849)용. */
    List<AutoTradeOrderLog> findPage(@Param("offset") int offset, @Param("limit") int limit);

    /** 전체 주문로그 건수(페이지네이션 totalElements, #849). */
    int countAll();

    /** 해당 심볼의 가장 최근 성공 BUY 로그(매수이유 역추적, #849) — 없으면 null. */
    AutoTradeOrderLog findLastBuySuccess(@Param("symbol") String symbol);
}
