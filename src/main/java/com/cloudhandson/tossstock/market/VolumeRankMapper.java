package com.cloudhandson.tossstock.market;

import org.apache.ibatis.annotations.Mapper;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface VolumeRankMapper {
    int deleteAll();

    int insert(VolumeRank row);

    List<VolumeRank> findLatest();

    LocalDateTime latestAsOf();
}
