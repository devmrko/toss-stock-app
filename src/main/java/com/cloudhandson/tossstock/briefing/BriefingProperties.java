package com.cloudhandson.tossstock.briefing;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Discord 브리핑 설정 — 값은 .env 경유. 웹훅 URL 은 비밀. */
@ConfigurationProperties(prefix = "briefing")
public record BriefingProperties(
        String webhookUrl,
        int watchlistLimit,
        String openCron,
        String closeCron) {

    public boolean enabled() {
        return webhookUrl != null && !webhookUrl.isBlank();
    }
}
