package com.cloudhandson.tossstock.search;

/** 종목 검색 결과 1건 (KR=UNIVERSE, US=us_universe). */
public record SearchHit(String symbol, String name, String market, String sector) {
}
