package com.cloudhandson.tossstock.autotrade;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.math.BigDecimal;
import java.util.List;

@Mapper
public interface AutoTradeOrderLogMapper {
    int insert(AutoTradeOrderLog log);

    /** 주문로그 페이지(성공/실패 모두, 최신순) — 운영 대시보드(#848/#849)용. */
    List<AutoTradeOrderLog> findPage(@Param("offset") int offset, @Param("limit") int limit);

    /** 전체 주문로그 건수(페이지네이션 totalElements, #849). */
    int countAll();

    /** 성공한 주문의 수수료+세금 합계(#849 요약). 0건이면 0. */
    BigDecimal totalFees();

    /** 해당 심볼의 가장 최근 성공 BUY 로그(매수이유 역추적, #849) — 없으면 null. */
    AutoTradeOrderLog findLastBuySuccess(@Param("symbol") String symbol);
}
