package com.cloudhandson.tossstock.news;

import java.time.LocalDateTime;

/** RSS 수집 1건. extId = dedup 키(url/제목 해시). */
public record NewsItem(String extId, String title, String url, String source, LocalDateTime publishedAt) {
}
