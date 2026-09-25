package com.cloudhandson.tossstock.autotrade;

import java.math.BigDecimal;

/** PER/PBR 조회 결과. 둘 다 null 가능(데이터 소스가 제공 안 하는 경우 — 예: 야후의 일부 한국 종목). */
public record Valuation(BigDecimal per, BigDecimal pbr) {
}
