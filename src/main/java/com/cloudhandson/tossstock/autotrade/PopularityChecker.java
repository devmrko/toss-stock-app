package com.cloudhandson.tossstock.autotrade;

import com.cloudhandson.tossstock.market.DailyOhlcv;

import java.util.Comparator;
import java.util.List;

/**
 * 인기(거래량 스파이크) 판정(순수 함수) — 대화 중 실측 방식(해성디에스 사례): 당일 거래량이
 * 최근 N일 평균 거래량 대비 threshold배 이상이면 "인기" 신호로 본다.
 * 설계: docs/design/808-auto-trade-engine/README.md §2
 */
public final class PopularityChecker {

    private PopularityChecker() {
    }

    /** rows: 한 종목의 최근 일봉(순서 무관, 내부에서 날짜순 정렬). window+1개 이상 필요. */
    public static boolean isVolumeSpike(List<DailyOhlcv> rows, int window, double threshold) {
        if (rows == null || rows.size() < window + 1) {
            return false; // 데이터 부족 — 판단 보류(스파이크 아님으로 취급)
        }
        List<DailyOhlcv> sorted = rows.stream()
                .sorted(Comparator.comparing(DailyOhlcv::getTradeDate))
                .toList();
        DailyOhlcv latest = sorted.get(sorted.size() - 1);
        List<DailyOhlcv> prior = sorted.subList(sorted.size() - 1 - window, sorted.size() - 1);
        double avg = prior.stream().mapToLong(DailyOhlcv::getVolume).average().orElse(0);
        if (avg <= 0 || latest.getVolume() == null) {
            return false;
        }
        return latest.getVolume() / avg >= threshold;
    }
}
