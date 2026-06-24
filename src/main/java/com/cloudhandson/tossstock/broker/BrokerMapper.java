package com.cloudhandson.tossstock.broker;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/** 매매처(증권사) 마스터 CRUD. (#442) */
@Mapper
public interface BrokerMapper {
    List<String> findAllNames();

    long count();

    /** 없으면 추가(멱등). */
    int merge(@Param("name") String name);
}
