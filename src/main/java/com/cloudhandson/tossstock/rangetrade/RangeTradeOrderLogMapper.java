package com.cloudhandson.tossstock.rangetrade;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.math.BigDecimal;
import java.util.List;

/** range_trade_order_log 접근(감사로그). */
@Mapper
public interface RangeTradeOrderLogMapper {
    int insert(RangeTradeOrderLog log);

    /** 주문로그 페이지(성공/실패 모두, 최신순) — 운영 대시보드(#848/#849)용. */
    List<RangeTradeOrderLog> findPage(@Param("offset") int offset, @Param("limit") int limit);

    /** 전체 주문로그 건수(페이지네이션 totalElements, #849). */
    int countAll();

    /** 성공한 주문의 수수료+세금 합계(#849 요약). 0건이면 0. */
    BigDecimal totalFees();
}
