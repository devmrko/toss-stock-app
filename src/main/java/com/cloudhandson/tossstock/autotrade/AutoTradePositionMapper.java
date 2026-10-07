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

    /**
     * 해당 종목이 가장 최근 HARD_STOP 또는 TRAIL_STOP으로 청산된 시각(없으면 null) — 손절 후
     * 즉시 재진입 쿨다운(#839, 2026-10-07)에 쓴다.
     */
    LocalDateTime lastStopExitAt(@Param("symbol") String symbol);

    /**
     * 가장 최근 HARD_STOP/TRAIL_STOP으로 청산된 포지션 1건(없으면 null) — 그 보유기간의
     * 활성 뉴스 테마 태그를 조회해 동일테마 재진입 판정(#840, 2026-10-07)에 쓴다.
     */
    AutoTradePosition findLastStopExited(@Param("symbol") String symbol);

    /**
     * EXITED 포지션 전체의 realized 손익 합계(스칼라, #845 2026-10-07) — checkCircuitBreaker가
     * findAll()로 전체 이력을 Java로 끌어와 재합산하던 것을 DB SUM 1건으로 대체(행 수 늘어나도
     * 빠름). 0건이면 0(NULL 아님).
     */
    BigDecimal realizedPnlTotal();
}
