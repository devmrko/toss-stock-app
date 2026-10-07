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

    /** EXITED 포지션 총 건수(#849 요약, 총 매매건수). */
    int countExited();

    /** exit_price > entry_price 인 EXITED 건수(#849 요약, 승). */
    int countWin();

    /** exit_price &lt; entry_price 인 EXITED 건수(#849 요약, 패) — 본전(동일가)은 승/패 어느 쪽에도 안 들어감. */
    int countLoss();

    /**
     * EXITED 포지션 각각의 매수/매도 레그 수수료+세금 합계(2026-10-08 정정, #808과 동일 버그
     * 수정 — 종목 전체 블랭킷 합산 대신 각 포지션 자신의 entry_at/exit_at으로 짝지은 로그만 합산).
     */
    BigDecimal realizedFeesTotal();
}
