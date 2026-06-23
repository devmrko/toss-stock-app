package com.cloudhandson.tossstock.news;

import com.cloudhandson.tossstock.market.UniverseMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 기사 → 시장/섹터/종목 S1-S5 분류 (OpenRouter Claude).
 * 설계: docs/design/425-news-s1s5/fn-news-pipeline.md
 */
@Component
public class NewsClassifier {

    private static final Logger log = LoggerFactory.getLogger(NewsClassifier.class);

    /** 허용 섹터 = SectorClassifier 버킷과 동일. */
    static final String SECTORS = "반도체,2차전지,디스플레이,바이오,의료기기,화장품,엔터,미디어,게임,"
            + "자동차,조선,방산,건설,철강금속,식음료,화학,에너지,통신,금융,유통소비재,운송,IT/인터넷,전자부품";
    private static final Set<String> SECTOR_SET = Set.of(SECTORS.split(","));

    private static final String SYSTEM = """
            You classify a Korean stock-market news headline's likely market impact.
            Output STRICT JSON only, no prose, no markdown fences.
            Schema: {"targets":[{"type":"SYMBOL|SECTOR|MARKET","name":"<상장사명 or 섹터 or MARKET>","level":"S1|S2|S3|S4|S5"}],"kind":"EVENT|SPECULATION","analysis":"<한국어 1~2문장>"}
            Levels(방향): S1 강한 악재, S2 약한 악재, S3 중립/무관, S4 약한 호재, S5 강한 호재.
            type=SYMBOL: 특정 상장사 뉴스. name 은 정확한 한국 상장사명(예: 삼성전자, SK하이닉스).
            type=SECTOR: 업종 전반 뉴스. name 은 다음 중 하나: %s
            type=MARKET: 거시/지수/전체장(예: Fed, 환율, 코스피 급락).
            한 기사에 여러 타겟 가능(종목+섹터+시장 동시). 가장 정확한 단위를 고른다.
            kind=EVENT: 실제 사실/이벤트(실적, 수주, 규제, 인수합병, 신제품, 공급계약, 소송).
            kind=SPECULATION: 전망/예측/차트분석/의견.
            주식과 무관하면 targets 에 MARKET S3 하나만.
            analysis 는 반드시 한국어로 무엇이 왜 호재/악재인지.
            """.formatted(SECTORS);

    private final NewsProperties props;
    private final UniverseMapper universeMapper;
    private final ObjectMapper om = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    public NewsClassifier(NewsProperties props, UniverseMapper universeMapper) {
        this.props = props;
        this.universeMapper = universeMapper;
    }

    public boolean enabled() {
        return props.openrouterKey() != null && !props.openrouterKey().isBlank();
    }

    /** 기사 제목 분류 → 해석된 결과. 실패 시 null. */
    public ClassifyResult classify(String title) {
        String raw = call(title);
        if (raw == null) {
            return null;
        }
        Parsed p = parse(raw);
        return p == null ? null : resolve(p);
    }

    private String call(String title) {
        try {
            String body = om.writeValueAsString(Map.of(
                    "model", props.openrouterModel(),
                    "max_tokens", 400,
                    "messages", List.of(
                            Map.of("role", "system", "content", SYSTEM),
                            Map.of("role", "user", "content", "기사: " + title + "\nReturn the JSON now."))));
            HttpRequest req = HttpRequest.newBuilder(URI.create(props.openrouterUrl()))
                    .header("Authorization", "Bearer " + props.openrouterKey())
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(40))
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (res.statusCode() >= 400) {
                log.warn("OpenRouter HTTP {}: {}", res.statusCode(), clip(res.body()));
                return null;
            }
            return om.readTree(res.body()).path("choices").path(0).path("message").path("content").asText(null);
        } catch (Exception e) {
            log.warn("LLM 호출 실패: {}", e.toString());
            return null;
        }
    }

    /** 모델 원문(JSON, 코드펜스 가능) → 구조화. 실패 시 null. */
    Parsed parse(String raw) {
        try {
            JsonNode n = om.readTree(stripFences(raw));
            List<Target> targets = new ArrayList<>();
            for (JsonNode t : n.path("targets")) {
                targets.add(new Target(
                        t.path("type").asText("MARKET").toUpperCase(),
                        t.path("name").asText("").trim(),
                        t.path("level").asText("S3").toUpperCase()));
            }
            return new Parsed(targets,
                    n.path("kind").asText("EVENT").toUpperCase(),
                    n.path("analysis").asText(""));
        } catch (Exception e) {
            log.warn("LLM JSON 파싱 실패: {}", e.toString());
            return null;
        }
    }

    /** 타겟 해석: SYMBOL 회사명→6자리 코드, SECTOR 검증, MARKET. CSV/sentiment 조립. */
    ClassifyResult resolve(Parsed p) {
        Map<String, String> keyToLevel = new LinkedHashMap<>();  // key(코드/섹터/MARKET) → level
        for (Target t : p.targets()) {
            String level = normLevel(t.level());
            String key = switch (t.type()) {
                case "SYMBOL" -> universeMapper.findCodeByName(t.name());        // 없으면 null → 드롭
                case "SECTOR" -> SECTOR_SET.contains(t.name()) ? t.name() : null;
                case "MARKET" -> "MARKET";
                default -> null;
            };
            if (key != null) {
                keyToLevel.merge(key, level, (a, b) -> strength(b) >= strength(a) ? b : a);
            }
        }
        if (keyToLevel.isEmpty()) {
            keyToLevel.put("MARKET", "S3");
        }
        String targetsCsv = String.join(",", keyToLevel.keySet());
        String sentimentCsv = keyToLevel.entrySet().stream()
                .map(e -> e.getKey() + ":" + e.getValue()).reduce((a, b) -> a + "," + b).orElse("");
        int maxStrength = keyToLevel.values().stream().mapToInt(NewsClassifier::strength).max().orElse(0);
        return new ClassifyResult(targetsCsv, sentimentCsv, p.kind(), p.analysis(), maxStrength);
    }

    private static String normLevel(String lv) {
        return (lv != null && lv.matches("S[1-5]")) ? lv : "S3";
    }

    record Target(String type, String name, String level) {
    }

    record Parsed(List<Target> targets, String kind, String analysis) {
    }

    public record ClassifyResult(String targetsCsv, String sentimentCsv, String kind,
                                 String analysis, int maxStrength) {
    }

    private static String stripFences(String s) {
        String t = s.trim();
        if (t.startsWith("```")) {
            t = t.replaceAll("^```[a-zA-Z]*\\s*", "").replaceAll("\\s*```$", "");
        }
        return t;
    }

    private static String clip(String s) {
        return s == null ? "" : (s.length() > 200 ? s.substring(0, 200) : s);
    }

    public static int strength(String level) {
        return switch (level == null ? "" : level.toUpperCase()) {
            case "S1", "S5" -> 2;
            case "S2", "S4" -> 1;
            default -> 0;
        };
    }
}
