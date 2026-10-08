package com.cloudhandson.tossstock.autotrade;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.CookieManager;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * PER/PBR 조회 — 한국 종목은 네이버 모바일 API, 미국 종목은 야후 파이낸스 API.
 * 둘 다 비공식(문서화 안 된) 엔드포인트라 언제든 응답 형식이 바뀌거나 막힐 수 있음 —
 * 실패 시 조용히 null 반환(호출측이 ValuationChecker에서 fail-closed 처리).
 * 설계: docs/design/808-auto-trade-engine/fn-valuation-client.md
 *
 * 2026-10-07(#836, QA 발견): 호출측(AutoTradeScheduler.scanCandidates는 매 틱, #835 수정 후
 * processHolding도 뉴스 식은 보유종목마다 매 틱)이 캐싱 없이 매분 이 메서드를 호출 — 저평가+
 * 상대강세가 며칠 유지되는 종목 하나만으로도 하루 1,440회씩 네이버/야후를 때려 레이트리밋/차단
 * 위험(테스트 픽스처에 있는 HTTP 429가 실제로 재현될 수 있음). PER/PBR은 분 단위로 바뀌지 않으므로
 * 짧은 TTL 캐시로 호출량을 줄인다.
 * 2026-10-07(#836 코덱스 리뷰 반영): 실패(null)를 성공과 같은 15분으로 캐시하면, API 일시 장애
 * "한 틱"의 영향이 15분간 고정돼 그 사이 보유종목이 "확인 불가"를 "저평가/상대강세 아님"으로 오인해
 * 매도될 위험이 커짐(매수 차단엔 안전한 방향이지만 매도 트리거엔 그렇지 않음) — 실패는 짧게(1분)만
 * 캐시해서 재시도 간격은 벌려주되(API 폭주 방지) 장애의 영향이 오래 고정되진 않게 한다. getValuation
 * (PER/PBR)과 getIndexReturnPct(지수 수익률, #828의 US 상대강세 판정에 사용) 둘 다 적용 — 전자만
 * 캐싱하면 US 종목은 지수 조회가 매 틱 그대로 남는다는 걸 코덱스 리뷰로 확인.
 */
@Component
public class ValuationClient {

    private static final Logger log = LoggerFactory.getLogger(ValuationClient.class);
    private static final Duration TIMEOUT = Duration.ofSeconds(8);
    private static final Duration SUCCESS_CACHE_TTL = Duration.ofMinutes(15);
    private static final Duration FAILURE_CACHE_TTL = Duration.ofMinutes(1);

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(TIMEOUT)
            .cookieHandler(new CookieManager())
            .build();
    private final ObjectMapper om = new ObjectMapper();
    private volatile String yahooCrumb;
    private final Map<String, Cached<Valuation>> valuationCache = new ConcurrentHashMap<>();
    private final Map<String, Cached<Double>> indexReturnCache = new ConcurrentHashMap<>();
    private final Map<String, Cached<IndexRiskSignal>> riskSignalCache = new ConcurrentHashMap<>();

    private record Cached<T>(T value, Instant fetchedAt) {
        boolean expired() {
            Duration ttl = value == null ? FAILURE_CACHE_TTL : SUCCESS_CACHE_TTL;
            return Duration.between(fetchedAt, Instant.now()).compareTo(ttl) >= 0;
        }
    }

    public Valuation getValuation(String symbol, String market) {
        String key = market + ":" + symbol;
        Cached<Valuation> cached = valuationCache.get(key);
        if (cached != null && !cached.expired()) {
            return cached.value();
        }
        Valuation fresh = fetchValuation(symbol, market);
        valuationCache.put(key, new Cached<>(fresh, Instant.now()));
        return fresh;
    }

    private Valuation fetchValuation(String symbol, String market) {
        try {
            return "US".equalsIgnoreCase(market) ? fetchYahoo(symbol) : fetchNaver(symbol);
        } catch (Exception e) {
            log.warn("밸류에이션 조회 실패(symbol={}, market={}): {}", symbol, market, e.toString());
            return null;
        }
    }

    private Valuation fetchNaver(String symbol) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder(URI.create("https://m.stock.naver.com/api/stock/" + symbol + "/integration"))
                .header("User-Agent", "Mozilla/5.0")
                .timeout(TIMEOUT)
                .GET().build();
        HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() >= 400) {
            return null;
        }
        JsonNode root = om.readTree(res.body());
        BigDecimal per = null;
        BigDecimal pbr = null;
        for (JsonNode item : root.path("totalInfos")) {
            String code = item.path("code").asText("");
            String value = item.path("value").asText(null);
            if (value == null) {
                continue;
            }
            BigDecimal parsed = parseNaverRatio(value);
            if ("per".equals(code)) {
                per = parsed;
            } else if ("pbr".equals(code)) {
                pbr = parsed;
            }
        }
        return new Valuation(per, pbr);
    }

    /** "12.85배" -> 12.85. 파싱 실패 시 null(적자 등 "-"도 여기서 null 처리). */
    private static BigDecimal parseNaverRatio(String value) {
        try {
            String cleaned = value.replace("배", "").trim();
            return new BigDecimal(cleaned);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 연도별 실적(매출/영업이익/순이익/부채비율/배당) — 컨센서스(추정) 연도 제외, 실제 결산만.
     * KR=네이버, US=야후(2026-09-30 추가). 실패 시 null(fail-closed).
     */
    public AnnualFinancials getAnnualFinancials(String symbol, String market) {
        try {
            return "US".equalsIgnoreCase(market) ? fetchYahooFinancials(symbol) : fetchNaverFinancials(symbol);
        } catch (Exception e) {
            log.warn("연간 실적 조회 실패(symbol={}, market={}): {}", symbol, market, e.toString());
            return null;
        }
    }

    private AnnualFinancials fetchNaverFinancials(String symbol) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder(
                URI.create("https://m.stock.naver.com/api/stock/" + symbol + "/finance/annual"))
                .header("User-Agent", "Mozilla/5.0")
                .timeout(TIMEOUT)
                .GET().build();
        HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() >= 400) {
            return null;
        }
        JsonNode root = om.readTree(res.body()).path("financeInfo");
        List<String> actualYearKeys = new ArrayList<>();
        for (JsonNode t : root.path("trTitleList")) {
            if ("N".equals(t.path("isConsensus").asText())) {
                actualYearKeys.add(t.path("key").asText());
            }
        }
        actualYearKeys.sort(String::compareTo); // "YYYYMM" 문자열 정렬 = 시간순(과거→최근)

        JsonNode rows = root.path("rowList");
        List<AnnualFinancials.Year> years = new ArrayList<>();
        for (String key : actualYearKeys) {
            years.add(new AnnualFinancials.Year(key,
                    cellValue(rows, "매출액", key), cellValue(rows, "영업이익", key),
                    cellValue(rows, "당기순이익", key), cellValue(rows, "부채비율", key),
                    cellValue(rows, "주당배당금", key)));
        }
        return new AnnualFinancials(years);
    }

    /**
     * 야후 incomeStatementHistory/balanceSheetHistory/summaryDetail로 연도별 실적 구성.
     * 실측(2026-09-30, LOCO): operatingIncome·balanceSheet 필드가 거의 항상 null — 무료 API
     * 한계. 매출·순이익만 신뢰 가능. AnnualFinancials.Year에 그대로 담되(부채비율/영업이익은
     * null 가능), EarningsQualityChecker가 operatingProfit 전부 null인 경우를 별도 처리.
     */
    private AnnualFinancials fetchYahooFinancials(String symbol) throws IOException, InterruptedException {
        ensureYahooCrumb();
        AnnualFinancials f = fetchYahooFinancialsOnce(symbol);
        if (f == null) {
            refreshYahooCrumb();
            f = fetchYahooFinancialsOnce(symbol);
        }
        return f;
    }

    private AnnualFinancials fetchYahooFinancialsOnce(String symbol) throws IOException, InterruptedException {
        String url = "https://query1.finance.yahoo.com/v10/finance/quoteSummary/" + symbol
                + "?modules=incomeStatementHistory,balanceSheetHistory,summaryDetail&crumb=" + yahooCrumb;
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .header("User-Agent", "Mozilla/5.0")
                .timeout(TIMEOUT)
                .GET().build();
        HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() >= 400) {
            return null;
        }
        JsonNode results = om.readTree(res.body()).path("quoteSummary").path("result");
        if (!results.isArray() || results.isEmpty()) {
            return null;
        }
        JsonNode first = results.get(0);
        BigDecimal dividendPerShare = readRaw(first.path("summaryDetail").path("dividendRate"));

        JsonNode incomeStatements = first.path("incomeStatementHistory").path("incomeStatementHistory");
        JsonNode balanceSheets = first.path("balanceSheetHistory").path("balanceSheetStatements");

        List<AnnualFinancials.Year> years = new ArrayList<>();
        List<JsonNode> incomeList = new ArrayList<>();
        incomeStatements.forEach(incomeList::add);
        // 야후는 최신→과거 순으로 줌 — 과거→최신으로 뒤집는다.
        for (int i = incomeList.size() - 1; i >= 0; i--) {
            JsonNode y = incomeList.get(i);
            String endDate = y.path("endDate").path("fmt").asText("");
            BigDecimal revenue = readRaw(y.path("totalRevenue"));
            BigDecimal operatingProfit = readRaw(y.path("operatingIncome"));
            BigDecimal netIncome = readRaw(y.path("netIncome"));
            BigDecimal debtRatio = debtRatioFor(balanceSheets, endDate);
            years.add(new AnnualFinancials.Year(endDate, revenue, operatingProfit, netIncome, debtRatio, dividendPerShare));
        }
        return new AnnualFinancials(years);
    }

    /** 부채비율(%) = 총부채/자기자본*100 — 야후 실측상 US 종목은 거의 항상 null(무료 API 한계, fail-closed). */
    private static BigDecimal debtRatioFor(JsonNode balanceSheets, String endDate) {
        for (JsonNode b : balanceSheets) {
            if (!endDate.equals(b.path("endDate").path("fmt").asText(""))) {
                continue;
            }
            BigDecimal liab = readRaw(b.path("totalLiab"));
            BigDecimal equity = readRaw(b.path("totalStockholderEquity"));
            if (liab == null || equity == null || equity.signum() == 0) {
                return null;
            }
            return liab.divide(equity, java.math.MathContext.DECIMAL64).multiply(BigDecimal.valueOf(100));
        }
        return null;
    }

    private static BigDecimal cellValue(JsonNode rows, String title, String yearKey) {
        for (JsonNode row : rows) {
            if (title.equals(row.path("title").asText())) {
                String raw = row.path("columns").path(yearKey).path("value").asText(null);
                if (raw == null) {
                    return null;
                }
                try {
                    return new BigDecimal(raw.replace(",", ""));
                } catch (NumberFormatException e) {
                    return null; // "-"(데이터 없음) 등
                }
            }
        }
        return null;
    }

    private Valuation fetchYahoo(String symbol) throws IOException, InterruptedException {
        ensureYahooCrumb();
        Valuation v = fetchYahooOnce(symbol);
        if (v == null) {
            // 크럼 만료 가능성 — 한 번 재발급 후 재시도
            refreshYahooCrumb();
            v = fetchYahooOnce(symbol);
        }
        return v;
    }

    private Valuation fetchYahooOnce(String symbol) throws IOException, InterruptedException {
        String url = "https://query1.finance.yahoo.com/v10/finance/quoteSummary/" + symbol
                + "?modules=defaultKeyStatistics,summaryDetail&crumb=" + yahooCrumb;
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .header("User-Agent", "Mozilla/5.0")
                .timeout(TIMEOUT)
                .GET().build();
        HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() >= 400) {
            return null;
        }
        JsonNode root = om.readTree(res.body());
        JsonNode results = root.path("quoteSummary").path("result");
        if (!results.isArray() || results.isEmpty()) {
            return null;
        }
        JsonNode first = results.get(0);
        BigDecimal pbr = readRaw(first.path("defaultKeyStatistics").path("priceToBook"));
        BigDecimal per = readRaw(first.path("summaryDetail").path("trailingPE"));
        return new Valuation(per, pbr);
    }

    /**
     * 단일 심볼(주로 지수 ETF)의 최근 N거래일 수익률(%) — 야후 차트 API(v8/finance/chart).
     * #828(2026-10-05): Toss 캔들 API가 US ETF를 전혀 지원 안 함(실측: SPY/QQQ/VOO/IVV/DIA
     * 전부 0건) — 상대강세 지수(SPY) 계산을 이걸로 대체. 이 엔드포인트는 크럼이 필요 없음(실측
     * 확인, getValuation/getAnnualFinancials의 quoteSummary와 다른 엔드포인트). 실패 시 null.
     */
    public Double getIndexReturnPct(String symbol, int windowDays) {
        String key = symbol + ":" + windowDays;
        Cached<Double> cached = indexReturnCache.get(key);
        if (cached != null && !cached.expired()) {
            return cached.value();
        }
        Double fresh = fetchIndexReturnPct(symbol, windowDays);
        indexReturnCache.put(key, new Cached<>(fresh, Instant.now()));
        return fresh;
    }

    /**
     * 지수의 일간 수익률 + 최근 실현변동성(#883). 실패·데이터부족이면 null.
     *
     * <p>고정 % 임계는 연도별 차단율이 통제되지 않는다(실측: SPY 0.8~15.9%,
     * KOSPI 1.7~23.1%). 변동성으로 정규화하면 1.3% 내외로 안정되고, 필요한 과거가
     * 20일로 줄어 긴 캘리브레이션 창이 불필요해진다.
     *
     * <p>{@link #getIndexReturnPct} 와 같은 야후 호출·같은 캐시를 쓰지 않고 별도
     * 캐시를 둔다 — 반환형이 다르고, 상대강세 판정(#828)의 구간 수익률과 용도가 다르다.
     */
    public IndexRiskSignal getIndexRiskSignal(String symbol, int volWindowDays) {
        String key = symbol + ":vol:" + volWindowDays;
        Cached<IndexRiskSignal> cached = riskSignalCache.get(key);
        if (cached != null && !cached.expired()) {
            return cached.value();
        }
        IndexRiskSignal fresh = fetchIndexRiskSignal(symbol, volWindowDays);
        riskSignalCache.put(key, new Cached<>(fresh, Instant.now()));
        return fresh;
    }

    private IndexRiskSignal fetchIndexRiskSignal(String symbol, int volWindowDays) {
        List<Double> closes = fetchCloses(symbol);
        if (closes == null || closes.size() < volWindowDays + 2) {
            return null;
        }
        int n = closes.size();
        List<Double> rets = new ArrayList<>();
        for (int i = n - volWindowDays; i < n; i++) {
            double prev = closes.get(i - 1);
            if (prev == 0) {
                return null;
            }
            rets.add((closes.get(i) - prev) / prev * 100);
        }
        double mean = rets.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        double var = rets.stream().mapToDouble(r -> (r - mean) * (r - mean)).sum() / (rets.size() - 1);
        return new IndexRiskSignal(rets.get(rets.size() - 1), Math.sqrt(var));
    }

    /** 야후 6개월 일별 종가. 실패 시 null. (#883 — 구간수익률·변동성이 공유) */
    private List<Double> fetchCloses(String symbol) {
        try {
            HttpRequest req = HttpRequest.newBuilder(
                    URI.create("https://query1.finance.yahoo.com/v8/finance/chart/" + symbol
                            + "?range=6mo&interval=1d"))
                    .header("User-Agent", "Mozilla/5.0")
                    .timeout(TIMEOUT)
                    .GET().build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() >= 400) {
                return null;
            }
            JsonNode results = om.readTree(res.body()).path("chart").path("result");
            if (!results.isArray() || results.isEmpty()) {
                return null;
            }
            List<Double> closes = new ArrayList<>();
            for (JsonNode c : results.get(0).path("indicators").path("quote").path(0).path("close")) {
                if (!c.isNull()) {
                    closes.add(c.asDouble());
                }
            }
            return closes;
        } catch (Exception e) {
            log.warn("지수 종가 조회 실패(symbol={}): {}", symbol, e.toString());
            return null;
        }
    }

    private Double fetchIndexReturnPct(String symbol, int windowDays) {
        try {
            HttpRequest req = HttpRequest.newBuilder(
                    URI.create("https://query1.finance.yahoo.com/v8/finance/chart/" + symbol + "?range=6mo&interval=1d"))
                    .header("User-Agent", "Mozilla/5.0")
                    .timeout(TIMEOUT)
                    .GET().build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() >= 400) {
                return null;
            }
            JsonNode results = om.readTree(res.body()).path("chart").path("result");
            if (!results.isArray() || results.isEmpty()) {
                return null;
            }
            JsonNode closeNode = results.get(0).path("indicators").path("quote").path(0).path("close");
            List<Double> closes = new ArrayList<>();
            for (JsonNode c : closeNode) {
                if (!c.isNull()) {
                    closes.add(c.asDouble());
                }
            }
            int n = Math.min(closes.size(), windowDays + 1);
            if (n < 2) {
                return null;
            }
            double first = closes.get(closes.size() - n);
            double last = closes.get(closes.size() - 1);
            if (first == 0) {
                return null;
            }
            return (last - first) / first * 100;
        } catch (Exception e) {
            log.warn("지수 수익률 조회 실패(symbol={}): {}", symbol, e.toString());
            return null;
        }
    }

    private static BigDecimal readRaw(JsonNode node) {
        if (node == null || !node.has("raw")) {
            return null;
        }
        return BigDecimal.valueOf(node.path("raw").asDouble());
    }

    private synchronized void ensureYahooCrumb() throws IOException, InterruptedException {
        if (yahooCrumb == null) {
            refreshYahooCrumb();
        }
    }

    private synchronized void refreshYahooCrumb() throws IOException, InterruptedException {
        http.send(HttpRequest.newBuilder(URI.create("https://fc.yahoo.com"))
                .header("User-Agent", "Mozilla/5.0").timeout(TIMEOUT).GET().build(),
                HttpResponse.BodyHandlers.discarding());
        HttpResponse<String> crumbRes = http.send(
                HttpRequest.newBuilder(URI.create("https://query1.finance.yahoo.com/v1/test/getcrumb"))
                        .header("User-Agent", "Mozilla/5.0").timeout(TIMEOUT).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        yahooCrumb = crumbRes.body();
    }
}
