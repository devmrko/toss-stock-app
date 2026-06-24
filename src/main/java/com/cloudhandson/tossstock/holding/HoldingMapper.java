package com.cloudhandson.tossstock.holding;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface HoldingMapper {
    List<Holding> findAll();

    int insert(Holding h);

    int deleteById(@Param("id") Long id);

    /** 종목의 모든 거래 손절% 일괄 변경(포지션 손절% 수정). */
    int updateStopPctBySymbol(@Param("symbol") String symbol, @Param("stopPct") double stopPct);

    /** 종목의 모든 거래 매매처 일괄 변경(null=해제). */
    int updateBrokerBySymbol(@Param("symbol") String symbol, @Param("broker") String broker);
}
