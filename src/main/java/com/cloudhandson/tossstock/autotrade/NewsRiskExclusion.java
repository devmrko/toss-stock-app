package com.cloudhandson.tossstock.autotrade;

import com.cloudhandson.tossstock.news.StockNews;
import com.cloudhandson.tossstock.news.StockNewsMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 리스크 이벤트 기사가 난 종목 집합(#887) — <b>뉴스가 진입 경로에서 갖는 유일한 역할</b>.
 *
 * <p>#887 에서 뉴스를 진입 트리거에서 제외했다(측정: 뉴스일 진입 실현손익 -0.11%,
 * 같은 종목 뉴스 없는 날 +7.71%). 그러나 "살 이유"로는 못 써도 "사지 말 이유"로는 유효하다 —
 * 유상증자·횡령·상폐 공시가 난 종목을 스크리너 숫자만 보고 사면 안 된다.
 *
 * <p><b>매도 트리거보다 넓게 본다(의도된 비대칭).</b> {@link RiskEventDetector#judge} 는
 * #877 에서 제목 기반 LOSS/DELISTING 을 빼내 공시형(DILUTION)만 남겼다 — 매도 오탐은
 * 멀쩡한 포지션의 실현손실이기 때문이다. 반면 <b>매수 배제의 오탐은 기회비용뿐</b>이므로
 * 여기서는 {@link TitleGuard#riskFlagOf} 전체(DELISTING/GOVERNANCE/IMPAIRMENT/DILUTION/LOSS)를
 * OR 로 더한다. 이는 {@code CatalystQualifier} 가 매수 경로에서 이미 쓰던 보호 범위이며,
 * 뉴스 발굴을 제거하면서 그걸 잃지 않기 위함이다.
 *
 * 설계: docs/design/887-news-exclusion-filter/fn-excludedSymbols.md
 */
@Component
public class NewsRiskExclusion {

    private static final Logger log = LoggerFactory.getLogger(NewsRiskExclusion.class);

    private final StockNewsMapper newsMapper;

    public NewsRiskExclusion(StockNewsMapper newsMapper) {
        this.newsMapper = newsMapper;
    }

    /**
     * {@code since} 이후 리스크 기사가 난 종목 집합. 없으면 빈 집합(null 아님).
     *
     * <p>조회 실패는 <b>예외를 전파</b>한다 — 배제 목록을 모르는 상태로 후보를 등록하면
     * 유상증자 공시가 난 종목을 살 수 있다. 호출부가 사이클 전체를 포기한다(fail-closed).
     */
    public Set<String> excludedSymbols(LocalDateTime since) {
        if (since == null) {
            throw new IllegalArgumentException("since 가 null");
        }
        List<StockNews> news = newsMapper.riskCandidatesSince(since);
        Set<String> excluded = new HashSet<>();
        for (StockNews n : news) {
            if (riskFlagOf(n) == null) {
                continue;
            }
            addTargets(excluded, n.getTargets());
        }
        log.debug("리스크 배제 종목 {}건 (기사 {}건 검사, since={})", excluded.size(), news.size(), since);
        return excluded;
    }

    /**
     * 기사 1건의 리스크 종류(순수). 리스크가 아니면 null.
     * 매도 판정({@link RiskEventDetector#judge})을 먼저 쓰고, 거기서 빠진 제목 기반
     * 리스크를 {@link TitleGuard#riskFlagOf} 로 보충한다 — 클래스 주석의 비대칭 참조.
     */
    static String riskFlagOf(StockNews n) {
        if (n == null) {
            return null;
        }
        String flag = RiskEventDetector.judge(n.getTitle(), n.getFacts());
        return flag != null ? flag : TitleGuard.riskFlagOf(n.getTitle());
    }

    /** {@code targets} CSV 의 모든 항목을 집합에 넣는다. 섹터명이 섞여도 무해하다(종목코드와 겹치지 않음). */
    private static void addTargets(Set<String> out, String targets) {
        if (targets == null || targets.isBlank()) {
            return;
        }
        for (String t : targets.split(",")) {
            String symbol = t.trim();
            if (!symbol.isEmpty()) {
                out.add(symbol);
            }
        }
    }
}
