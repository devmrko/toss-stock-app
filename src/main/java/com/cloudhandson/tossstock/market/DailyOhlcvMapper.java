package com.cloudhandson.tossstock.market;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@Mapper
public interface DailyOhlcvMapper {
    int upsert(DailyOhlcv row);

    long count();

    LocalDate latestDate();

    LocalDate minDate();

    /** 최신 거래일 거래량 상위 N (universe 조인: name/market/sector, close→lastPrice). */
    List<VolumeRank> topByVolumeOnLatest(@Param("n") int n);

    /** 특정 거래일 직전 거래일의 종가들(symbol→close). */
    List<DailyOhlcv> prevCloseBefore(@Param("latest") LocalDate latest);

    /** 지정 종목들의 fromDate 이후 일봉(오름차순). 지표 계산용. */
    List<DailyOhlcv> recentForSymbols(@Param("symbols") List<String> symbols,
                                      @Param("fromDate") LocalDate fromDate);

    /** 커버리지(minCoverage 종목 이상) 충분한 최신 거래일 기준 상승/전체. {REF_DATE, PREV_DATE, UP, TOTAL}. */
    Map<String, Object> breadth(@Param("minCoverage") int minCoverage);

    /** 종목별 (fromDate 이후) 고점/저점 일괄. pairs=[{symbol, fromDate}...] → [{SYMBOL, MAXHIGH, MINLOW}]. */
    List<Map<String, Object>> peakTroughBatch(@Param("pairs") List<Map<String, Object>> pairs);

    /** 종목의 fromDate 이후 최고가/최저가. {MAXHIGH, MINLOW}. */
    Map<String, Object> rangeSince(@Param("symbol") String symbol, @Param("fromDate") LocalDate fromDate);

    /**
     * KR 유니버스 전 종목의 "최근 windowBars 거래일" 윈도우 통계 일괄 조회(#818 레인지 스캔 1차 스크리닝).
     * fromDate 는 스캔 범위를 줄이기 위한 하한(windowBars 개를 담을 만큼 넉넉히 줄 것).
     */
    List<DailyRangeStats> rangeStatsBatch(@Param("fromDate") LocalDate fromDate,
                                          @Param("windowBars") int windowBars);
}
