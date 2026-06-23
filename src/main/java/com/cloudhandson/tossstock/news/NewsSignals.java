package com.cloudhandson.tossstock.news;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 활성 뉴스 sentiment 목록 → key(코드/섹터/MARKET)별 최강 S레벨 맵. 순수. */
public final class NewsSignals {

    private NewsSignals() {
    }

    /** sentiments: ["005930:S5,반도체:S5","MARKET:S2,반도체:S2", ...] → {key: 최강레벨}. */
    public static Map<String, String> aggregate(List<String> sentiments) {
        Map<String, String> out = new LinkedHashMap<>();
        if (sentiments == null) {
            return out;
        }
        for (String csv : sentiments) {
            if (csv == null || csv.isBlank()) {
                continue;
            }
            for (String pair : csv.split(",")) {
                int i = pair.indexOf(':');
                if (i <= 0) {
                    continue;
                }
                String key = pair.substring(0, i).trim();
                String level = pair.substring(i + 1).trim();
                if (!level.matches("S[1-5]")) {
                    continue;
                }
                String cur = out.get(key);
                if (cur == null || NewsClassifier.strength(level) > NewsClassifier.strength(cur)) {
                    out.put(key, level);
                }
            }
        }
        return out;
    }
}
