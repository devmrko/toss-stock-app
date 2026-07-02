package com.cloudhandson.tossstock.reentry;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface ReentryWatchMapper {
    List<ReentryWatch> findActive();

    int insert(ReentryWatch watch);

    int markStage1(@Param("id") Long id, @Param("at") LocalDateTime at);

    /** stage2 도달 시 알림 시각 기록과 동시에 active=0 처리(반복 알림 방지). */
    int markStage2(@Param("id") Long id, @Param("at") LocalDateTime at);
}
