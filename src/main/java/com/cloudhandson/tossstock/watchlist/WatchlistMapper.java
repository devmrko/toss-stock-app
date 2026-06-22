package com.cloudhandson.tossstock.watchlist;

import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface WatchlistMapper {
    List<Watchlist> findAll();

    int insert(Watchlist watchlist);
}
