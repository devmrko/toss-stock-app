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

    /**
     * 섹터 평균 수익률(#874) — 원칙 §6-1 상대강도. since 이후 창에서 종목별
     * (마지막종가/첫종가-1)을 섹터로 평균한다. 수익률 내림차순.
     *
     * <p>두 가지 표본 조건이 있다.
     * <ul>
     *   <li>{@code minBars} — 창 내 봉이 이만큼 있는 종목만 센다. 없으면 데이터가 3봉뿐인
     *       종목의 3일 수익률과 20봉 종목의 20일 수익률이 섞여 섹터 순위가 왜곡된다
     *       (실측: 지수가 -4.25%인 창에서 섹터 평균이 대부분 양수로 나왔다).</li>
     *   <li>종목 3개 미만 섹터 제외 — 표본이 적으면 극단값이 나온다.</li>
     * </ul>
     */
    List<SectorReturn> sectorReturns(@Param("since") java.time.LocalDate since,
                                      @Param("minBars") int minBars);

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
