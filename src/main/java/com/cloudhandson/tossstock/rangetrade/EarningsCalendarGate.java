package com.cloudhandson.tossstock.rangetrade;

import com.cloudhandson.tossstock.market.UniverseMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.CookieManager;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Ellman "Banned Stocks" 게이트 — 예상 보유기간 안에 실적발표가 예정된 종목은 후보에서 제외.
 * 실적 갭으로 박스권이 한 번에 깨지는 걸 막는 용도(README §4).
 *
 * <p><b>데이터 소스(2026-10-01 Developer 단계 실측)</b> — 설계서는 "소스 미확정"이라 적었고 실제로 확인한 결과:
 * <ul>
 *   <li>네이버 모바일 API(<code>/api/stock/{code}/integration</code>)에 <code>irScheduleInfo</code> 필드는
 *       존재하지만 실측 10종목(005930·000660·035420·005380·012330·051910·068270·207940·035720·105560)
 *       전부 <code>null</code> — KR 실적발표 예정일 소스로 못 씀.</li>
 *   <li>야후 <code>quoteSummary?modules=calendarEvents</code> 는 KR 종목도 서픽스(.KS/.KQ)를 붙이면
 *       <code>calendarEvents.earnings.earningsDate</code> 를 돌려줌(실측: 005930.KS=2026-10-28,
 *       035720.KS=2026-11-06 등. 일부는 <code>isEarningsDateEstimate=true</code> 추정치, 058470.KQ 처럼
 *       빈 배열인 종목도 있음).</li>
 * </ul>
 * 그래서 야후 calendarEvents 를 소스로 쓰고, <b>데이터가 없거나 조회 실패면 통과(fail-open — 차단하지 않음)</b>.
 * 추정치(<code>isEarningsDateEstimate=true</code>)는 보수적으로 "예정 있음"으로 취급해 차단한다(스킵은 기회비용뿐,
 * 실적 갭은 손실이므로 비대칭).
 *
 * <p>설계: docs/design/818-range-trade-swing/README.md §4, §7, §12
 */
@Component
public class EarningsCalendarGate {

    private static final Logger log = LoggerFactory.getLogger(EarningsCalendarGate.class);
    private static final Duration TIMEOUT = Duration.ofSeconds(8);

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(TIMEOUT)
            .cookieHandler(new CookieManager())
            .build();
    private final ObjectMapper om = new ObjectMapper();
    private final UniverseMapper universeMapper;
    private volatile String yahooCrumb;

    public EarningsCalendarGate(UniverseMapper universeMapper) {
        this.universeMapper = universeMapper;
    }

    /** true = 예상 보유기간(today ~ today+holdingHorizonDays) 안에 실적발표 예정 → 후보 제외. */
    public boolean hasUpcomingEarnings(String symbol, int holdingHorizonDays) {
        List<LocalDate> dates;
        try {
            dates = fetchEarningsDates(yahooTicker(symbol));
        } catch (Exception e) {
            log.warn("실적발표 예정일 조회 실패(symbol={}) — 차단하지 않음(fail-open): {}", symbol, e.toString());
            return false;
        }
        if (dates.isEmpty()) {
            return false; // 데이터 없음 → 통과(fail-open)
        }
        LocalDate today = LocalDate.now();
        LocalDate horizon = today.plusDays(holdingHorizonDays);
        for (LocalDate d : dates) {
            if (!d.isBefore(today) && !d.isAfter(horizon)) {
                log.info("실적발표 예정으로 후보 제외: symbol={}, date={}, horizon={}일", symbol, d, holdingHorizonDays);
                return true;
            }
        }
        return false;
    }

    /** 국내 6자리 코드 → 야후 티커. KOSDAQ 은 .KQ, 그 외(KOSPI/ETF/미등록)는 .KS. */
    String yahooTicker(String symbol) {
        String market = null;
        try {
            market = universeMapper.findMarketBySymbol(symbol);
        } catch (RuntimeException e) {
            log.warn("시장구분 조회 실패(symbol={}) — .KS 로 가정: {}", symbol, e.toString());
        }
        return symbol + ("KOSDAQ".equalsIgnoreCase(market) ? ".KQ" : ".KS");
    }

    private List<LocalDate> fetchEarningsDates(String ticker) throws IOException, InterruptedException {
        ensureCrumb();
        List<LocalDate> dates = fetchOnce(ticker);
        if (dates == null) {
            refreshCrumb(); // 크럼 만료 가능성 — 한 번 재발급 후 재시도
            dates = fetchOnce(ticker);
        }
        return dates == null ? List.of() : dates;
    }

    /** null = 조회 실패(재시도 대상), 빈 리스트 = 조회됐지만 실적일 데이터 없음. */
    private List<LocalDate> fetchOnce(String ticker) throws IOException, InterruptedException {
        String url = "https://query1.finance.yahoo.com/v10/finance/quoteSummary/" + ticker
                + "?modules=calendarEvents&crumb=" + yahooCrumb;
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
        List<LocalDate> out = new ArrayList<>();
        for (JsonNode node : results.get(0).path("calendarEvents").path("earnings").path("earningsDate")) {
            String fmt = node.path("fmt").asText(null);
            if (fmt == null) {
                continue;
            }
            try {
                out.add(LocalDate.parse(fmt));
            } catch (RuntimeException e) {
                log.warn("실적발표일 파싱 실패(ticker={}, fmt={})", ticker, fmt);
            }
        }
        return out;
    }

    private synchronized void ensureCrumb() throws IOException, InterruptedException {
        if (yahooCrumb == null) {
            refreshCrumb();
        }
    }

    private synchronized void refreshCrumb() throws IOException, InterruptedException {
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
