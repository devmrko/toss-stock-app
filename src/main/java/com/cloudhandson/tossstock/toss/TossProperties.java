package com.cloudhandson.tossstock.toss;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 토스 Open API 설정 — 값은 .env → application.yml 경유 주입. */
@ConfigurationProperties(prefix = "toss")
public record TossProperties(String baseUrl, String clientKey, String secretKey) {
}
