package com.cloudhandson.tossstock.market;

import java.math.BigDecimal;

/**
 * 섹터 평균 수익률(#874) — 원칙 §6-1 "시장·섹터 상대강도 Top/Bottom 5".
 *
 * @param symbols 집계에 들어간 종목 수. 표본이 적으면 극단값이 나오므로 3개 미만 섹터는
 *                쿼리에서 제외한다(설계 §2.3).
 */
public record SectorReturn(String sector, BigDecimal returnPct, int symbols) {
}
