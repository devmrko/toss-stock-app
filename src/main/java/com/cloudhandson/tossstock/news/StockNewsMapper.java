package com.cloudhandson.tossstock.news;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface StockNewsMapper {

    boolean existsByExtId(@Param("extId") String extId);

    int insertReceived(StockNews news);

    int updateAnalyzed(@Param("id") Long id, @Param("targets") String targets,
                       @Param("sentiment") String sentiment, @Param("rationale") String rationale,
                       @Param("kind") String kind, @Param("model") String model,
                       @Param("expiresAt") LocalDateTime expiresAt);

    List<StockNews> findReceived(@Param("limit") int limit);

    /** 활성(미만료) ANALYZED 신호. key 가 null 이면 전체, 아니면 targets LIKE. S3-only 제외. */
    List<StockNews> active(@Param("key") String key, @Param("limit") int limit);

    /** 활성(미만료) 신호들의 sentiment CSV 목록 (뱃지 집계용). */
    List<String> activeSentiments();

    int expireOld();
}
