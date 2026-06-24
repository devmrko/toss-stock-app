package com.cloudhandson.tossstock.news;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/** 뉴스 분류 설정 — 값은 .env 경유. LLM 은 OpenAI 호환(base-url + api-key + model). */
@ConfigurationProperties(prefix = "news")
public record NewsProperties(
        List<String> feeds,
        String llmApiKey,
        String llmModel,
        String llmBaseUrl,
        long intervalMs,
        int maxPerRun) {
}
