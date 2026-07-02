package com.cloudhandson.tossstock.reentry;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 재진입 알림 설정 — 값은 .env 경유. 웹훅 URL 은 비밀. 설계: docs/design/reentry-alert/README.md §4 */
@ConfigurationProperties(prefix = "reentry-alert")
public record ReentryAlertProperties(String webhookUrl, String cron) {

    public boolean enabled() {
        return webhookUrl != null && !webhookUrl.isBlank();
    }
}
