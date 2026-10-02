package com.cloudhandson.tossstock.autotrade;

import com.cloudhandson.tossstock.news.NewsSignals;
import com.cloudhandson.tossstock.news.StockNews;
import com.cloudhandson.tossstock.news.StockNewsMapper;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;

/**
 * 원칙 §2/§3-7 "밸류에이션 재평가 여지" — 이미 싼 것과는 별개로, 자본배분(배당확대·자사주매입·소각)
 * 또는 성장형 대규모 투자(증설·수주·공급계약) 촉매 뉴스가 있으면 재평가 여지로 인정.
 * ValuationChecker(절대 PER/PBR)와 OR 결합해서 저평가 게이트 완화.
 * 설계: docs/design/808-auto-trade-engine/README.md §2(2026-09-29 수정)
 * 2026-09-30: 검증 단계 표본 확보를 위해 성장형 촉매(증설/투자/수주/공급계약)로 범위 확대 —
 * 삼성전기(009150)처럼 PER/PBR이 절대적으로 비싸도(117배/11배) 대규모 투자·수주 뉴스로
 * 재평가 여지를 인정받을 수 있게. 자본환원(배당·자사주)만 인정하던 원래 취지보다 넓어진 것이라,
 * "이미 비싼 종목을 성장 기대만으로 사는" 리스크가 커짐을 인지하고 채택(사용자 명시적 승인).
 * 2026-10-02: 실적서프라이즈/가이던스상향 키워드 추가 — 미국 대형 기술주는 PBR이 구조적으로
 * 높아(자사주매입으로 장부가 축소) 절대 PBR 상한(3.0)이 사실상 미국주식 전면 차단처럼 작동함을
 * 실측 확인(마이크론 PBR 11.65 — "괴물 실적"급 S5 뉴스 5건에도 밸류에이션+기존 촉매 키워드
 * 둘 다 탈락, 이후 실제로 +2.46% 상승해 기회비용 확인). 최근 5일 실제 S5 EVENT 헤드라인 80건을
 * 실측해 "호실적/사상최대실적/깜짝실적/재평가" 같은 반복 패턴을 추려 키워드로 추가(감으로 안 찍음).
 * "실적" 단독은 너무 범용(실적 개선 "기대" 같은 추측성 기사도 걸림)이라 제외, 강한 확인형 표현만 채택.
 */
@Component
public class CapitalReturnCatalystDetector {

    private static final List<String> KEYWORDS = List.of(
            "자사주", "소각", "배당", "buyback", "repurchase", "dividend",
            "증설", "투자", "수주", "공급계약", "capex", "expansion", "investment", "contract",
            "호실적", "최대 실적", "깜짝 실적", "어닝서프라이즈", "가이던스 상향", "재평가",
            "beats on earnings", "beat on earnings", "strong guidance", "earnings surprise", "guidance raised");
    private static final int HOT_LEVEL_MIN = 4;

    private final StockNewsMapper newsMapper;

    public CapitalReturnCatalystDetector(StockNewsMapper newsMapper) {
        this.newsMapper = newsMapper;
    }

    public boolean hasRecentCatalyst(String symbol) {
        List<StockNews> active = newsMapper.active(symbol, 20);
        for (StockNews n : active) {
            if (!"EVENT".equals(n.getKind())) {
                continue;
            }
            String level = NewsSignals.levelOf(n.getSentiment(), symbol);
            if (level == null || Integer.parseInt(level.substring(1)) < HOT_LEVEL_MIN) {
                continue;
            }
            String haystack = (safe(n.getTitle()) + " " + safe(n.getRationale())).toLowerCase(Locale.ROOT);
            if (KEYWORDS.stream().anyMatch(k -> haystack.contains(k.toLowerCase(Locale.ROOT)))) {
                return true;
            }
        }
        return false;
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }
}
