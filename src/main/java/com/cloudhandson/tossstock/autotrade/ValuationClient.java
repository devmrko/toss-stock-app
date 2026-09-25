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
import java.util.ArrayList;
import java.util.List;

/**
 * PER/PBR 조회 — 한국 종목은 네이버 모바일 API, 미국 종목은 야후 파이낸스 API.
 * 둘 다 비공식(문서화 안 된) 엔드포인트라 언제든 응답 형식이 바뀌거나 막힐 수 있음 —
 * 실패 시 조용히 null 반환(호출측이 ValuationChecker에서 fail-closed 처리).
 * 설계: docs/design/808-auto-trade-engine/fn-valuation-client.md
 */
@Component
public class ValuationClient {

    private static final Logger log = LoggerFactory.getLogger(ValuationClient.class);
    private static final Duration TIMEOUT = Duration.ofSeconds(8);

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(TIMEOUT)
            .cookieHandler(new CookieManager())
            .build();
    private final ObjectMapper om = new ObjectMapper();
    private volatile String yahooCrumb;

    public Valuation getValuation(String symbol, String market) {
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
     * KR(네이버)만 지원 — US는 후속 과제(#808 §12). 실패 시 null(fail-closed).
     */
    public AnnualFinancials getAnnualFinancials(String symbol, String market) {
        if (!"KR".equalsIgnoreCase(market)) {
            return null;
        }
        try {
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
        } catch (Exception e) {
            log.warn("연간 실적 조회 실패(symbol={}): {}", symbol, e.toString());
            return null;
        }
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
