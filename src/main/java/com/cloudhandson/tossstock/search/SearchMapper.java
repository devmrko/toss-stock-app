package com.cloudhandson.tossstock.search;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface SearchMapper {
    /** 이름/코드로 KR+US 통합 검색. 코드 정확/접두 우선, 이름 포함. */
    List<SearchHit> search(@Param("q") String q, @Param("limit") int limit);
}
