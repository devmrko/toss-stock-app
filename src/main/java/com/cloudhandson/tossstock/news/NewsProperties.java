package com.cloudhandson.tossstock.news;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/** 뉴스 분류 설정 — 값은 .env 경유. */
@ConfigurationProperties(prefix = "news")
public record NewsProperties(
        List<String> feeds,
        String openrouterKey,
        String openrouterModel,
        String openrouterUrl,
        long intervalMs,
        int maxPerRun) {
}
