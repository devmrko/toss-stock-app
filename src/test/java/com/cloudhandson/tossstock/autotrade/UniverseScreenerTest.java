package com.cloudhandson.tossstock.autotrade;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * #887 원칙 §3 기반 후보 스크리너(순수).
 * 설계: docs/design/887-news-exclusion-filter/fn-screen.md §10
 */
class UniverseScreenerTest {

    private static final BigDecimal KR_FLOOR = BigDecimal.valueOf(300_000_000);
    private static final BigDecimal US_FLOOR = BigDecimal.valueOf(200_000);

    /** 급등률 상한 3.0, 상한 40종목, KR 만 활성. */
    private static UniverseScreener.ScreenParams params() {
        return new UniverseScreener.ScreenParams(Set.of("KR"), KR_FLOOR, US_FLOOR, 3.0, 40);
    }

    /** 지수 20일 +0% — 상대강세 기준선이 0 이 된다. */
    private static Map<String, Double> krIndexFlat() {
        return Map.of("KR", 0.0);
    }

    /** 통과 기준선: 유동성 10억, 20일 +1%, 급등률 +1%. */
    private static ScreeningRow passing(String symbol) {
        return row(symbol, "KR", "101", "100", "1000000000", "100", 60);
    }

    private static ScreeningRow row(String symbol, String market, String latest, String before20,
                                     String turnover, String lowRecent, int bars) {
        return new ScreeningRow(symbol, market,
                latest == null ? null : new BigDecimal(latest),
                before20 == null ? null : new BigDecimal(before20),
                turnover == null ? null : new BigDecimal(turnover),
                lowRecent == null ? null : new BigDecimal(lowRecent), bars);
    }

    private static UniverseScreener.ScreenResult screen(ScreeningRow... rows) {
        return UniverseScreener.screen(List.of(rows), Set.of(), krIndexFlat(), params());
    }

    // ---- 기준선 ----

    @Test
    void 기준선_종목은_통과한다() {
        UniverseScreener.ScreenResult r = screen(passing("005930"));
        assertThat(r.selected()).extracting(ScreenCandidate::symbol).containsExactly("005930");
    }

    // ---- 단계별 탈락 ----

    @Test
    void 유동성_미달은_탈락한다() {
        assertThat(screen(row("005930", "KR", "101", "100", "299999999", "100", 60)).selected()).isEmpty();
    }

    @Test
    void 급등률이_상한_이상이면_탈락한다() {
        // 저점 100 → 현재 103 = +3.0% ≥ 상한 3.0 (경계는 탈락)
        assertThat(screen(row("005930", "KR", "103", "100", "1000000000", "100", 60)).selected()).isEmpty();
        // +2.99% 는 통과
        assertThat(screen(row("005930", "KR", "102.99", "100", "1000000000", "100", 60)).selected())
                .hasSize(1);
    }

    @Test
    void 지수보다_약하면_탈락한다() {
        // 20일 -1% < 지수 0%
        assertThat(screen(row("005930", "KR", "99", "100", "1000000000", "98", 60)).selected()).isEmpty();
        // 지수와 같으면(초과수익 0) 탈락 — "초과"를 요구한다
        assertThat(screen(row("005930", "KR", "100", "100", "1000000000", "100", 60)).selected()).isEmpty();
    }

    @Test
    void 바가_21개_미만이면_탈락한다() {
        assertThat(screen(row("005930", "KR", "101", "100", "1000000000", "100", 20)).selected()).isEmpty();
        assertThat(screen(row("005930", "KR", "101", "100", "1000000000", "100", 21)).selected()).hasSize(1);
    }

    @Test
    void 활성_시장이_아니면_제외된다() {
        // US 는 #885/#886 미해결로 범위 외 — 퍼널 total 에도 세지 않는다
        UniverseScreener.ScreenResult r = UniverseScreener.screen(
                List.of(row("TSM", "US", "101", "100", "1000000000", "100", 60)),
                Set.of(), Map.of("US", 0.0), params());
        assertThat(r.selected()).isEmpty();
        assertThat(r.funnel().total()).isZero();
    }

    // ---- fail-closed ----

    @Test
    void 최근저점_결측은_탈락한다() {
        assertThat(screen(row("005930", "KR", "101", "100", "1000000000", null, 60)).selected()).isEmpty();
        assertThat(screen(row("005930", "KR", "101", "100", "1000000000", "0", 60)).selected()).isEmpty();
    }

    @Test
    void 기준일_종가_결측은_탈락한다() {
        assertThat(screen(row("005930", "KR", "101", null, "1000000000", "100", 60)).selected()).isEmpty();
    }

    @Test
    void 거래대금_null은_0으로_보고_탈락한다() {
        assertThat(screen(row("005930", "KR", "101", "100", null, "100", 60)).selected()).isEmpty();
    }

    @Test
    void 지수_수익률이_없으면_그_시장_전체가_탈락한다() {
        UniverseScreener.ScreenResult r = UniverseScreener.screen(
                List.of(passing("005930"), passing("000660")), Set.of(), Map.of(), params());
        assertThat(r.selected()).isEmpty();
        assertThat(r.funnel().afterLiquidity()).isEqualTo(2);
        assertThat(r.funnel().afterRelativeStrength()).isZero();
    }

    // ---- 리스크 배제 ----

    @Test
    void 배제_집합의_종목은_탈락한다() {
        UniverseScreener.ScreenResult r = UniverseScreener.screen(
                List.of(passing("005930"), passing("000660")),
                Set.of("005930"), krIndexFlat(), params());
        assertThat(r.selected()).extracting(ScreenCandidate::symbol).containsExactly("000660");
        assertThat(r.funnel().afterExtension()).isEqualTo(2);
        assertThat(r.funnel().afterRiskExclusion()).isEqualTo(1);
    }

    // ---- 정렬 / 상한 ----

    @Test
    void 거래대금_내림차순_상위N으로_잘린다() {
        UniverseScreener.ScreenParams p = new UniverseScreener.ScreenParams(
                Set.of("KR"), KR_FLOOR, US_FLOOR, 3.0, 2);
        UniverseScreener.ScreenResult r = UniverseScreener.screen(List.of(
                row("AAA111", "KR", "101", "100", "1000000000", "100", 60),
                row("BBB222", "KR", "101", "100", "3000000000", "100", 60),
                row("CCC333", "KR", "101", "100", "2000000000", "100", 60)),
                Set.of(), krIndexFlat(), p);
        assertThat(r.selected()).extracting(ScreenCandidate::symbol)
                .containsExactly("BBB222", "CCC333");
    }

    @Test
    void 거래대금_동률이면_종목코드_오름차순() {
        UniverseScreener.ScreenResult r = screen(
                row("000660", "KR", "101", "100", "1000000000", "100", 60),
                row("005930", "KR", "101", "100", "1000000000", "100", 60));
        assertThat(r.selected()).extracting(ScreenCandidate::symbol)
                .containsExactly("000660", "005930");
    }

    @Test
    void topN이_0이면_후보가_없다() {
        // 킬스위치
        UniverseScreener.ScreenParams p = new UniverseScreener.ScreenParams(
                Set.of("KR"), KR_FLOOR, US_FLOOR, 3.0, 0);
        assertThat(UniverseScreener.screen(List.of(passing("005930")), Set.of(), krIndexFlat(), p)
                .selected()).isEmpty();
    }

    @Test
    void 입력_리스트를_변형하지_않는다() {
        // 제자리 정렬로 호출부 리스트를 훼손하면 안 된다.
        List<ScreeningRow> rows = new java.util.ArrayList<>(List.of(
                row("BBB222", "KR", "101", "100", "3000000000", "100", 60),
                row("AAA111", "KR", "101", "100", "1000000000", "100", 60)));
        UniverseScreener.screen(rows, Set.of(), krIndexFlat(), params());
        assertThat(rows).extracting(ScreeningRow::symbol).containsExactly("BBB222", "AAA111");
    }

    // ---- 퍼널 / 경계 ----

    @Test
    void 퍼널_숫자가_단계별_잔존수와_일치한다() {
        UniverseScreener.ScreenResult r = UniverseScreener.screen(List.of(
                passing("005930"),                                              // 통과
                row("000660", "KR", "101", "100", "1", "100", 60),              // 유동성 탈락
                row("035420", "KR", "99", "100", "1000000000", "98", 60),       // 상대강세 탈락
                row("051910", "KR", "110", "100", "1000000000", "100", 60),     // 급등률 탈락
                row("006400", "KR", "101", "100", "1000000000", "100", 10),     // 바 부족
                passing("012330")),                                             // 배제
                Set.of("012330"), krIndexFlat(), params());
        ScreenFunnel f = r.funnel();
        assertThat(f.total()).isEqualTo(6);
        assertThat(f.afterBars()).isEqualTo(5);
        assertThat(f.afterLiquidity()).isEqualTo(4);
        assertThat(f.afterRelativeStrength()).isEqualTo(3);
        assertThat(f.afterExtension()).isEqualTo(2);
        assertThat(f.afterRiskExclusion()).isEqualTo(1);
        assertThat(f.selected()).isEqualTo(1);
    }

    @Test
    void 빈_입력은_빈_결과와_0_퍼널() {
        UniverseScreener.ScreenResult r = UniverseScreener.screen(
                List.of(), Set.of(), krIndexFlat(), params());
        assertThat(r.selected()).isEmpty();
        assertThat(r.funnel()).isEqualTo(ScreenFunnel.empty());

        UniverseScreener.ScreenResult n = UniverseScreener.screen(
                null, null, null, params());
        assertThat(n.selected()).isEmpty();
    }

    @Test
    void params가_null이면_예외() {
        assertThatThrownBy(() -> UniverseScreener.screen(List.of(), Set.of(), Map.of(), null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void null_행은_무시된다() {
        assertThat(UniverseScreener.screen(java.util.Arrays.asList(null, passing("005930")),
                Set.of(), krIndexFlat(), params()).selected()).hasSize(1);
    }

    // ---- 노트 ----

    @Test
    void 노트는_스크리너_접두사로_시작하고_수치를_담는다() {
        ScreenCandidate c = new ScreenCandidate("005930", "KR",
                BigDecimal.valueOf(123_400_000_000L), 1.5, 3.2);
        String note = UniverseScreener.noteOf(LocalDate.of(2026, 10, 9), c);
        assertThat(note).startsWith("스크리너(2026-10-09, ")
                .contains("거래대금 1234억").contains("초과수익 +3.2%p").contains("급등 +1.5%");
        assertThat(note.length()).isLessThanOrEqualTo(500);
    }
}
