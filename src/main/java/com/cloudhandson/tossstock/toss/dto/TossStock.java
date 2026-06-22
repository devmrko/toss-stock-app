package com.cloudhandson.tossstock.toss.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record TossStock(
        String symbol,
        String name,
        String englishName,
        String market,
        String isinCode,
        String securityType,
        String status,
        String currency) {
}
