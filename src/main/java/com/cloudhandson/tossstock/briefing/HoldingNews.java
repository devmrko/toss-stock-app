package com.cloudhandson.tossstock.briefing;

/** 보유 종목에 매칭된 뉴스 1건(브리핑용). level=S1~S5. */
public record HoldingNews(String name, String level, String title) {
}
