package com.cloudhandson.tossstock.autotrade;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #886 환율 응답 파싱. 설계: docs/design/886-us-fx-order-sizing/fn-usdKrw.md §10
 *
 * <p>캐시 TTL·HTTP 경로는 외부 의존이라 여기서 다루지 않는다 — 순수한 응답 해석만 고정한다.
 * (TTL 자체는 {@code ValuationClient} 와 같은 검증된 패턴을 그대로 쓴다.)
 */
class FxRateCacheTest {

    private static final ObjectMapper OM = new ObjectMapper();

    private static com.fasterxml.jackson.databind.JsonNode json(String s) throws Exception {
        return OM.readTree(s);
    }

    @Test
    void 마지막_종가를_읽는다() throws Exception {
        assertThat(FxRateCache.lastClose(json("""
                {"chart":{"result":[{"indicators":{"quote":[{"close":[1342.6,1343.8,1340.78]}]}}]}}""")))
                .isEqualByComparingTo("1340.78");
    }

    @Test
    void 뒤쪽_null은_건너뛰고_직전_유효값을_쓴다() throws Exception {
        // FX 는 거래가 연속이지만 휴일 구간에 null 이 끼는 경우가 있다.
        assertThat(FxRateCache.lastClose(json("""
                {"chart":{"result":[{"indicators":{"quote":[{"close":[1342.6,1339.10,null,null]}]}}]}}""")))
                .isEqualByComparingTo("1339.10");
    }

    @Test
    void 종가가_전부_null이면_null() throws Exception {
        assertThat(FxRateCache.lastClose(json("""
                {"chart":{"result":[{"indicators":{"quote":[{"close":[null,null]}]}}]}}"""))).isNull();
    }

    @Test
    void 구조가_다르면_null() throws Exception {
        assertThat(FxRateCache.lastClose(json("{}"))).isNull();
        assertThat(FxRateCache.lastClose(json("""
                {"chart":{"error":"Not Found"}}"""))).isNull();
        assertThat(FxRateCache.lastClose(json("""
                {"chart":{"result":[{"indicators":{"quote":[{}]}}]}}"""))).isNull();
    }

    @Test
    void 소수_둘째자리로_정규화한다() throws Exception {
        assertThat(FxRateCache.lastClose(json("""
                {"chart":{"result":[{"indicators":{"quote":[{"close":[1340.783456]}]}}]}}""")))
                .isEqualByComparingTo("1340.78");
    }
}
