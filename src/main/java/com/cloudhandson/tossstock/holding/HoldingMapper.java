package com.cloudhandson.tossstock.holding;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface HoldingMapper {
    List<Holding> findAll();

    int insert(Holding h);

    int deleteById(@Param("id") Long id);
}
