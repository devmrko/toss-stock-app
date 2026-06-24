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

    /** 6자리 코드 → 섹터. 없으면 null. */
    String findSectorBySymbol(@org.apache.ibatis.annotations.Param("symbol") String symbol);

    /** 여러 코드 → {symbol, sector} 일괄(N+1 제거). */
    List<Universe> sectorsForSymbols(@org.apache.ibatis.annotations.Param("symbols") List<String> symbols);

    int insert(Universe u);

    int updateSector(@org.apache.ibatis.annotations.Param("symbol") String symbol,
                     @org.apache.ibatis.annotations.Param("sector") String sector);
}
