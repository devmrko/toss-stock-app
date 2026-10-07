package com.cloudhandson.tossstock.rangetrade;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/** range_trade_position 접근(#808 AutoTradePositionMapper 와 분리된 테이블). */
@Mapper
public interface RangeTradePositionMapper {

    List<RangeTradePosition> findHolding();

    List<RangeTradePosition> findAll();

    RangeTradePosition findById(@Param("id") Long id);

    int insert(RangeTradePosition position);

    int markExited(@Param("id") Long id, @Param("exitPrice") BigDecimal exitPrice,
                   @Param("exitReason") String exitReason, @Param("exitAt") LocalDateTime exitAt);

    int countHolding();

    /** EXITED 포지션 전체의 realized 손익 합계(스칼라, #845) — #808과 동일 성능개선. 0건이면 0. */
    BigDecimal realizedPnlTotal();
}
