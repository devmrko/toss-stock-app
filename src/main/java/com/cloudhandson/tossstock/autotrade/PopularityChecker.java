package com.cloudhandson.tossstock.autotrade;

import com.cloudhandson.tossstock.market.DailyOhlcv;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.Comparator;
import java.util.List;

/**
 * 인기 판정(순수 함수) — (거래량 스파이크) OR (당일 가격변동률).
 * 2026-09-29 수정: 삼성전기(009150) 사례 — 6조원 투자 발표 당일 +2.55% 움직였는데 거래량비는
 * 0.90배로 탈락. 초대형·고유동성 종목은 평소 기저 거래량 자체가 커서 상대 거래량비가 잘 안 튀고,
 * 오히려 유동성이 좋을수록 적은 거래로도 가격이 효율적으로 반응한다. 그래서 거래량비 하나로만
 * "인기"를 판정하면 이미 충분히 유동적인 대형주를 구조적으로 놓친다 — 가격변동률을 OR로 추가.
 * 설계: docs/design/808-auto-trade-engine/README.md §2
 */
public final class PopularityChecker {

    private PopularityChecker() {
    }

    /** rows: 한 종목의 최근 일봉(순서 무관, 내부에서 날짜순 정렬). window+1개 이상 필요. */
    public static boolean isVolumeSpike(List<DailyOhlcv> rows, int window, double threshold) {
        List<DailyOhlcv> sorted = sortedOrNull(rows, window);
        if (sorted == null) {
            return false; // 데이터 부족 — 판단 보류(스파이크 아님으로 취급)
        }
        DailyOhlcv latest = sorted.get(sorted.size() - 1);
        List<DailyOhlcv> prior = sorted.subList(sorted.size() - 1 - window, sorted.size() - 1);
        double avg = prior.stream().mapToLong(DailyOhlcv::getVolume).average().orElse(0);
        if (avg <= 0 || latest.getVolume() == null) {
            return false;
        }
        return latest.getVolume() / avg >= threshold;
    }

    /** 당일(최신 거래일) 종가가 전일 대비 pctThreshold(%) 이상 움직였는지(상승만 인정 — 호재 판정이므로). */
    public static boolean isPriceMoveSignificant(List<DailyOhlcv> rows, double pctThreshold) {
        List<DailyOhlcv> sorted = sortedOrNull(rows, 1);
        if (sorted == null) {
            return false;
        }
        BigDecimal latest = sorted.get(sorted.size() - 1).getCloseP();
        BigDecimal prev = sorted.get(sorted.size() - 2).getCloseP();
        if (latest == null || prev == null || prev.signum() <= 0) {
            return false;
        }
        double pct = latest.subtract(prev).divide(prev, MathContext.DECIMAL64).doubleValue() * 100;
        return pct >= pctThreshold;
    }

    /** 거래량 스파이크 OR 가격변동 — 둘 중 하나만 있어도 "인기"로 인정. */
    public static boolean isPopular(List<DailyOhlcv> rows, int window, double volumeThreshold, double priceMovePct) {
        return isVolumeSpike(rows, window, volumeThreshold) || isPriceMoveSignificant(rows, priceMovePct);
    }

    private static List<DailyOhlcv> sortedOrNull(List<DailyOhlcv> rows, int minPriorDays) {
        if (rows == null || rows.size() < minPriorDays + 1) {
            return null;
        }
        return rows.stream().sorted(Comparator.comparing(DailyOhlcv::getTradeDate)).toList();
    }
}
