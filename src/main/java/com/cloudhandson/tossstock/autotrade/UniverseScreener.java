package com.cloudhandson.tossstock.autotrade;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 원칙 §3 기반 후보 스크리너(순수 함수, #887) — 뉴스를 진입 트리거에서 빼낸 자리를 메운다.
 *
 * <p><b>왜 바꿨나.</b> 뉴스를 유일한 진입 트리거로 쓰던 구조가 음수 기대값으로 측정됐다.
 * KOSPI 가 +183.6% 오른 2025-01~2026-10 구간에 뉴스일(S4/S5) 진입의 실현손익은
 * <b>-0.11%</b>(수수료 전, N=2,149)였고 같은 종목의 뉴스 없는 날은 +7.71%, 지수 보유는
 * +5.70% 였다. 급등률을 통제해도 전 구간에서 4.6~8.6%p 열등했으므로 급등 필터로는 고칠
 * 수 없고, 뉴스 후 10거래일을 기다려도 수수료 후 +0.59% 였다.
 * 측정: docs/analysis/2026-10-09-news-trigger-negative-expectancy.md
 *
 * <p><b>fail-closed.</b> 판정에 필요한 데이터가 없으면 통과시키지 않는다. 급등 구간의
 * 하드손절률이 최대 89.9% 로 측정됐으므로 "모르면 사지 않는다"가 기대값상 유리하다.
 * ({@link PriceExtension} 은 fail-open 이지만 그건 이미 후보가 된 종목의 장중 판정이고,
 * 여기는 후보 선별이므로 보수적으로 간다.)
 *
 * <p><b>외부 I/O 없음.</b> 지수 수익률조차 입력으로 받는다 — KR 은 스냅샷 안의 069500 으로
 * 계산 가능하지만 US 확장 시 야후 경로가 필요하므로 처음부터 경계를 밖에 둔다.
 *
 * 설계: docs/design/887-news-exclusion-filter/fn-screen.md
 */
public final class UniverseScreener {

    /** 후보 노트의 자동 등록 식별 접두사. 이 접두사가 없으면 수동 등록으로 본다. */
    public static final String NOTE_PREFIX = "스크리너(";

    /** 후보 노트가 들어가는 컬럼 길이(auto_trade_candidate.valuation_note VARCHAR2(500)). */
    private static final int NOTE_MAX = 500;

    private UniverseScreener() {
    }

    /**
     * @param markets        활성 시장(예: {@code {"KR"}}). 여기 없는 시장 행은 퍼널에서도 제외한다
     * @param minTurnoverKr  KR 유동성 하한(원)
     * @param minTurnoverUs  US 유동성 하한(USD)
     * @param maxExtensionPct 급등률 상한(%) — 이상이면 탈락
     * @param topN           등록 상한. 0 이하면 후보 0건(킬스위치)
     */
    public record ScreenParams(Set<String> markets, BigDecimal minTurnoverKr,
                                BigDecimal minTurnoverUs, double maxExtensionPct, int topN) {
    }

    public record ScreenResult(List<ScreenCandidate> selected, ScreenFunnel funnel) {
    }

    /**
     * 스냅샷을 1차 선별·정렬해 상위 N 후보와 단계별 퍼널을 낸다.
     *
     * @param rows                종목별 로컬 집계. null 이면 빈 결과
     * @param excluded            리스크 기사 보유 종목(배제). null 이면 빈 집합
     * @param indexReturnByMarket 시장별 지수 20거래일 수익률(%). 없으면 그 시장 전체 탈락
     * @param params              설정 스냅샷. <b>null 이면 IllegalArgumentException</b>
     */
    public static ScreenResult screen(List<ScreeningRow> rows, Set<String> excluded,
                                       Map<String, Double> indexReturnByMarket, ScreenParams params) {
        if (params == null) {
            // 설정 누락은 프로그래밍 오류다. 기본값으로 조용히 매수 경로를 열면 안 된다.
            throw new IllegalArgumentException("ScreenParams 가 null");
        }
        if (rows == null || rows.isEmpty()) {
            return new ScreenResult(List.of(), ScreenFunnel.empty());
        }
        Set<String> markets = params.markets() == null ? Set.of() : params.markets();
        Set<String> blocked = excluded == null ? Set.of() : excluded;
        Map<String, Double> indexReturns = indexReturnByMarket == null ? Map.of() : indexReturnByMarket;

        // 1) 시장 필터 + 데이터 충분
        List<ScreeningRow> stage = new ArrayList<>();
        int total = 0;
        for (ScreeningRow r : rows) {
            if (r == null || r.market() == null || !markets.contains(r.market())) {
                continue;   // 범위 외 시장 — 퍼널에 세지 않는다
            }
            total++;
            if (r.symbol() != null && !r.symbol().isBlank()
                    && r.latestClose() != null && r.latestClose().signum() > 0
                    && r.bars() >= 21) {
                stage.add(r);
            }
        }
        int afterBars = stage.size();

        // 2) 유동성 (원칙 §3-6 시장성)
        stage = stage.stream().filter(r -> {
            BigDecimal floor = "US".equalsIgnoreCase(r.market())
                    ? params.minTurnoverUs() : params.minTurnoverKr();
            BigDecimal turnover = r.avgTurnover20() == null ? BigDecimal.ZERO : r.avgTurnover20();
            return floor == null || turnover.compareTo(floor) >= 0;
        }).collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        int afterLiquidity = stage.size();

        // 3) 상대강세 (원칙 §3-7) — 지수 수익률 없으면 그 시장 전체 탈락(fail-closed)
        stage = stage.stream()
                .filter(r -> {
                    Double excess = excessReturnPctOf(r, indexReturns.get(r.market()));
                    return excess != null && excess > 0;
                })
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        int afterRelativeStrength = stage.size();

        // 4) 급등률 상한 — 선반영 차단(fail-closed)
        stage = stage.stream()
                .filter(r -> {
                    Double ext = extensionPctOf(r);
                    return ext != null && ext < params.maxExtensionPct();
                })
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        int afterExtension = stage.size();

        // 5) 리스크 이벤트 배제 — 뉴스가 진입 경로에서 갖는 유일한 역할
        stage = stage.stream()
                .filter(r -> !blocked.contains(r.symbol()))
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        int afterRiskExclusion = stage.size();

        // 6) 정렬 + 절단. 거래대금 내림차순, 동률이면 종목코드 오름차순(결정론적).
        List<ScreenCandidate> selected = stage.stream()
                .map(r -> new ScreenCandidate(r.symbol(), r.market(),
                        r.avgTurnover20() == null ? BigDecimal.ZERO : r.avgTurnover20(),
                        extensionPctOf(r), excessReturnPctOf(r, indexReturns.get(r.market()))))
                .sorted(Comparator.comparing(ScreenCandidate::turnover).reversed()
                        .thenComparing(ScreenCandidate::symbol))
                .limit(Math.max(0, params.topN()))
                .toList();

        return new ScreenResult(selected, new ScreenFunnel(total, afterBars, afterLiquidity,
                afterRelativeStrength, afterExtension, afterRiskExclusion, selected.size()));
    }

    /**
     * 5거래일 저점 대비 상승률(%). {@link PriceExtension#pctAboveRecentLow} 와 같은 공식이지만
     * 기준가가 실시간 체결가가 아니라 최신 종가다 — 발굴은 장외에도 돌기 때문이다.
     *
     * @return 판정 불가(저점/종가 결측·저점 0 이하)면 null → 호출부가 탈락시킨다
     */
    static Double extensionPctOf(ScreeningRow r) {
        if (r == null || r.lowRecent() == null || r.lowRecent().signum() <= 0
                || r.latestClose() == null || r.latestClose().signum() <= 0) {
            return null;
        }
        return r.latestClose().subtract(r.lowRecent())
                .divide(r.lowRecent(), MathContext.DECIMAL64).doubleValue() * 100;
    }

    /**
     * 지수 대비 20거래일 초과수익(%p). 어느 한쪽이라도 없으면 null(fail-closed).
     */
    static Double excessReturnPctOf(ScreeningRow r, Double indexReturnPct) {
        if (r == null || indexReturnPct == null
                || r.closeBefore20() == null || r.closeBefore20().signum() <= 0
                || r.latestClose() == null) {
            return null;
        }
        double stockReturn = r.latestClose().subtract(r.closeBefore20())
                .divide(r.closeBefore20(), MathContext.DECIMAL64).doubleValue() * 100;
        return stockReturn - indexReturnPct;
    }

    /** 후보 노트. 접두사 {@link #NOTE_PREFIX} 가 자동 등록 식별자다. 500자에서 절단한다. */
    static String noteOf(LocalDate asOf, ScreenCandidate c) {
        String note = String.format("%s%s, 거래대금 %.0f억·초과수익 %+.1f%%p·급등 %+.1f%%)",
                NOTE_PREFIX, asOf, c.turnover().doubleValue() / 100_000_000d,
                c.excessReturnPct(), c.extensionPct());
        return note.length() <= NOTE_MAX ? note : note.substring(0, NOTE_MAX);
    }
}
