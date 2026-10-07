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

    /** EXITED 포지션 총 건수(#849 요약, 총 매매건수). */
    int countExited();

    /** exit_price > entry_price 인 EXITED 건수(#849 요약, 승). */
    int countWin();

    /** exit_price &lt; entry_price 인 EXITED 건수(#849 요약, 패) — 본전(동일가)은 승/패 어느 쪽에도 안 들어감. */
    int countLoss();

    /**
     * EXITED 포지션 각각의 매수/매도 레그 수수료+세금 합계(2026-10-08 정정) — 종목 전체의
     * 성공 주문로그를 블랭킷 합산하면 "아직 보유 중인 재진입분의 매수 수수료"까지 섞여 들어가
     * 실현손익이 실제보다 더 나쁘게 보이는 버그가 있었다(053800/066570 실측: 사용자 토스 앱
     * 숫자와 59~81원 어긋남 — 둘 다 재진입 매수 1건의 수수료만큼). 각 EXITED 포지션의
     * entry_at/exit_at으로 그 포지션 "자신의" BUY/SELL 로그만 짝지어 합산 — 열려있는
     * 포지션의 매수 수수료는 포함되지 않는다.
     */
    BigDecimal realizedFeesTotal();

    /**
     * 날짜별(exit_at 기준) 실현손익 집계(#851) — 토스 앱의 일별 보기와 직접 대조하기 위함.
     * 전체 누적 합계만 보여주면 "오늘 하루" 숫자와 비교할 때 다른 날짜 거래가 섞여 안 맞아
     * 보이는 혼동이 생긴다(2026-10-08 실사례: 10/2 거래가 섞여 10/7 단독 합계와 어긋나 보임).
     * 날짜 내림차순.
     */
    List<DailyRealizedPnl> dailyRealizedSummary();
}
