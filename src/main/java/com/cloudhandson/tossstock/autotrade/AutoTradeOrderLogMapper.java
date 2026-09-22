package com.cloudhandson.tossstock.autotrade;

import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface AutoTradeOrderLogMapper {
    int insert(AutoTradeOrderLog log);
}
