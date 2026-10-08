package com.cloudhandson.tossstock.toss.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * 수수료 요율표 1행 — GET /api/v1/commissions (#863).
 * 설계: docs/design/863-dynamic-commission-rate/README.md
 *
 * <p>응답이 주는 그대로 문자열로 받는다(요율도 {@code "0.00015"} 형태) — 파싱·검증은
 * {@code CommissionRateCache} 가 한다. 비정상 값이 와도 DTO 단계에서 깨지지 않게 하려는 것이다.
 *
 * <p>실측(2026-10-08): KR 0.00015/endDate 9999-12-31, US 0.001/endDate 2026-10-09.
 * US 의 endDate 는 #863 등록 시점(2026-10-08)에서 하루 밀려 있었다 — 요율 변경 공지가
 * 아니라 "현재 유효"를 표현하는 롤링 값으로 보인다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TossCommission(String marketCountry, String commissionRate,
                              String startDate, String endDate) {
}
