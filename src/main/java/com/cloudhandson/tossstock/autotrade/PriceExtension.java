package com.cloudhandson.tossstock.autotrade;

import com.cloudhandson.tossstock.market.DailyOhlcv;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.Comparator;
import java.util.List;

/**
 * "최근 저점 대비 지금 얼마나 올라와 있는가"(순수 함수, #859). #838의 급등 필터가 쓰던
 * {@link PopularityChecker#priceMovePct}(일봉 최신종가 vs 전일종가)는 장중 판단 시점엔
 * 어제 값이거나 미완성 바라, "지금 이미 N% 올라있다"는 사실 자체를 측정하지 못했다.
 * 게다가 기준선이 전일종가라 어제 급등한 종목은 다음날 음수로 보여 그대로 통과했다
 * (실측: 안랩 2차 -7.6%·포스코퓨처엠 -5.4%·삼성SDI -1.4%, 셋 다 손실).
 *
 * 최근 N거래일 최저 종가를 기준선으로 삼고 실시간 체결가와 비교하면 실측 9건에서 수익 3건
 * (+2.8/+3.3/+4.5%)과 손실 5건(+9.9~+20.5%)이 깨끗하게 갈렸다.
 */
public final class PriceExtension {

    private PriceExtension() {
    }

    /**
     * 최근 {@code lookbackDays}거래일 최저 종가 대비 {@code currentPrice}의 상승률(%).
     * 데이터가 lookbackDays보다 적으면 있는 만큼으로 계산한다(신규상장 등을 막지 않기 위함).
     *
     * @return 상승률(%). 측정 불가(일봉 없음·현재가 없음·저점 0 이하)면 null — 호출부는
     *         이 필터를 적용하지 않는다(fail-open, 설계서 §5).
     */
    public static Double pctAboveRecentLow(List<DailyOhlcv> rows, BigDecimal currentPrice, int lookbackDays) {
        if (rows == null || rows.isEmpty() || currentPrice == null || lookbackDays <= 0) {
            return null;
        }
        List<DailyOhlcv> sorted = rows.stream().sorted(Comparator.comparing(DailyOhlcv::getTradeDate)).toList();
        List<DailyOhlcv> window = sorted.subList(Math.max(0, sorted.size() - lookbackDays), sorted.size());
        BigDecimal low = null;
        for (DailyOhlcv d : window) {
            BigDecimal close = d.getCloseP();
            if (close != null && (low == null || close.compareTo(low) < 0)) {
                low = close;
            }
        }
        if (low == null || low.signum() <= 0) {
            return null;
        }
        return currentPrice.subtract(low).divide(low, MathContext.DECIMAL64).doubleValue() * 100;
    }
}
