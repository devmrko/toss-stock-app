package com.cloudhandson.tossstock.autotrade;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface AutoTradePositionMapper {
    List<AutoTradePosition> findHolding();

    /** 서킷브레이커용 전체 평가손익 계산에 쓰는 전체 이력(보유+청산). */
    List<AutoTradePosition> findAll();

    AutoTradePosition findById(@Param("id") Long id);

    int insert(AutoTradePosition position);

    int updatePeak(@Param("id") Long id, @Param("peakPrice") BigDecimal peakPrice);

    int markExited(@Param("id") Long id, @Param("exitPrice") BigDecimal exitPrice,
                    @Param("exitReason") String exitReason, @Param("exitAt") LocalDateTime exitAt);

    int countHolding();

    /**
     * 해당 종목이 가장 최근 NEWS_FADED로 청산된 시각(없으면 null) — #828 유지경로 재매수 쿨다운(#835
     * QA, 2026-10-07)에 쓴다. 다른 사유(하드/트레일스탑 등)로 청산된 건은 대상이 아님.
     */
    LocalDateTime lastNewsFadedExitAt(@Param("symbol") String symbol);
}
