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
                       @Param("expiresAt") LocalDateTime expiresAt,
                       @Param("facts") String facts);

    List<StockNews> findReceived(@Param("limit") int limit);

    /** 활성(미만료) ANALYZED 신호. key 가 null 이면 전체, 아니면 targets LIKE. S3-only 제외. */
    List<StockNews> active(@Param("key") String key, @Param("limit") int limit);

    /** 활성(미만료) 신호들의 sentiment CSV 목록 (뱃지 집계용). */
    List<String> activeSentiments();

    /** 최근 N일간 해당 섹터의 EVENT+호재(S4/S5) 누적 건수(만료 여부 무관) — 거시 트렌드 참고용(게이트 아님). */
    int countSectorHotEvents(@Param("sector") String sector, @Param("since") LocalDateTime since);

    /** 최근 since 이후 EVENT 뉴스(만료 여부 무관) — 자동 후보 발굴용(#808 2026-09-29). */
    List<StockNews> findRecentEvents(@Param("since") LocalDateTime since);

    int expireOld();

    /**
     * 기간 내 해당 종목 타겟 뉴스(만료 여부 무관) — 손절된 포지션의 보유기간에 활성이었던
     * 테마 태그를 복원해 동일테마 재진입 판정(#840, 2026-10-07)에 쓴다.
     */
    List<StockNews> forSymbolBetween(@Param("symbol") String symbol, @Param("from") LocalDateTime from,
                                      @Param("to") LocalDateTime to);
}
