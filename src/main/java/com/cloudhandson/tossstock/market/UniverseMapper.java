package com.cloudhandson.tossstock.market;

import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface UniverseMapper {
    long count();

    long countWithoutSector();

    List<Universe> findAll();

    /** 상장사명 → 6자리 코드(정확 일치). 없으면 null. */
    String findCodeByName(@org.apache.ibatis.annotations.Param("name") String name);

    int insert(Universe u);

    int updateSector(@org.apache.ibatis.annotations.Param("symbol") String symbol,
                     @org.apache.ibatis.annotations.Param("sector") String sector);
}
