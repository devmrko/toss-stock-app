package com.cloudhandson.tossstock.toss.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** GET /api/v1/buying-power 응답. 설계: docs/design/802-toss-buying-power/README.md */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TossBuyingPower(String currency, String cashBuyingPower) {
}
