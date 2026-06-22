package com.cloudhandson.tossstock.toss.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** 일봉 캔들. 토스 응답은 문자열 수치 — 변환은 서비스 경계에서. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TossCandle(
        String timestamp,
        String openPrice,
        String highPrice,
        String lowPrice,
        String closePrice,
        String volume,
        String currency) {
}
