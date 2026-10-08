package com.cloudhandson.tossstock.news;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 기사 1건에서 뽑은 "사실"(해석 아님) — 매수 촉매 자격 판정의 입력.
 * 설계: docs/design/865-news-catalyst-qualifier/README.md §2.1
 *
 * <p>등급(S1~S5) 단독은 신뢰할 수 없음이 확인돼(정의문을 빼면 등급이 붕괴), 판정을
 * LLM 의 한 글자에 맡기지 않고 **사실을 뽑아 코드의 규칙으로** 판정한다.
 *
 * <p>boolean 이 아니라 {@link Boolean} 인 이유: LLM 이 필드를 누락했을 때 "false"와
 * "모름"을 구분해야 한다. 둘의 처리가 다르다 — {@code CatalystQualifier} 의 비대칭
 * null 규칙 참조.
 */
public record NewsFacts(
        Boolean confirmed,
        Boolean isTransaction,
        Boolean materialAmount,
        Boolean recurring,
        Boolean secularDemand,
        Boolean exportGlobal,
        Boolean shareholderReturn,
        Boolean priceAlreadyMoved,
        String beneficiary,
        String riskFlag,
        String why) {

    private static final ObjectMapper OM = new ObjectMapper();

    /** DB 에 저장된 JSON → 사실. null/빈값/파싱불가면 null(= 추출 실패로 취급). */
    public static NewsFacts parse(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return from(OM.readTree(json));
        } catch (Exception e) {
            return null;
        }
    }

    /** LLM 응답의 facts 노드 → 사실. 노드가 없거나 객체가 아니면 null. */
    public static NewsFacts from(JsonNode n) {
        if (n == null || !n.isObject()) {
            return null;
        }
        return new NewsFacts(
                bool(n, "confirmed"),
                bool(n, "isTransaction"),
                bool(n, "materialAmount"),
                bool(n, "recurring"),
                bool(n, "secularDemand"),
                bool(n, "exportGlobal"),
                bool(n, "shareholderReturn"),
                bool(n, "priceAlreadyMoved"),
                text(n, "beneficiary"),
                text(n, "riskFlag"),
                text(n, "why"));
    }

    /** 저장용 JSON. 실패 시 null(저장 생략 → 자격 판정은 fail-closed). */
    public String toJson() {
        try {
            return OM.writeValueAsString(this);
        } catch (Exception e) {
            return null;
        }
    }

    /** 누락/비(非)boolean 은 null("모름") — false 로 뭉개지 않는다. */
    private static Boolean bool(JsonNode n, String field) {
        JsonNode v = n.get(field);
        return (v == null || !v.isBoolean()) ? null : v.booleanValue();
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.get(field);
        if (v == null || !v.isTextual()) {
            return null;
        }
        String s = v.textValue().trim();
        return s.isEmpty() ? null : s;
    }
}
