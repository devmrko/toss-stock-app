package com.cloudhandson.tossstock.market;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDate;
import java.util.List;

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
}
