package com.cloudhandson.tossstock.market;

import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface UniverseMapper {
    long count();

    long countWithoutSector();

    List<Universe> findAll();

    int insert(Universe u);

    int updateSector(@org.apache.ibatis.annotations.Param("symbol") String symbol,
                     @org.apache.ibatis.annotations.Param("sector") String sector);
}
