package com.cloudhandson.tossstock.news;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #867 RSS pubDate → KST 환산. 설계: docs/design/867-timestamp-timezone-normalization/README.md §5
 *
 * <p>이전 구현은 {@code toLocalDateTime()} 으로 오프셋을 변환 없이 버려서, +0900 피드는
 * 우연히 맞고 GMT/EST 피드는 9시간(또는 13~14시간) 틀어졌다.
 */
class RssClientTest {

    @Test
    void kst_피드는_값이_바뀌지_않는다() {
        // 인수조건 5 — 기존에 맞았던 것은 그대로여야 한다(hankyung/yna/etnews = +0900).
        assertThat(RssClient.parseDate("Thu, 08 Oct 2026 20:14:00 +0900"))
                .isEqualTo(LocalDateTime.of(2026, 10, 8, 20, 14));
    }

    @Test
    void gmt_피드는_kst로_환산된다() {
        // 인수조건 4 — 다우존스(feeds.content.dowjones.io)가 GMT 표기를 쓴다.
        // 이전 구현이면 11:14 로 저장돼 9시간 과거로 기록됐다.
        assertThat(RssClient.parseDate("Thu, 08 Oct 2026 11:14:00 GMT"))
                .isEqualTo(LocalDateTime.of(2026, 10, 8, 20, 14));
    }

    @Test
    void iso_utc_표기도_환산된다() {
        assertThat(RssClient.parseDate("2026-10-08T11:14:00Z"))
                .isEqualTo(LocalDateTime.of(2026, 10, 8, 20, 14));
    }

    @Test
    void edt_피드는_kst로_환산된다() {
        // CNBC 는 EST(-5)/EDT(-4) 로 DST 에 따라 바뀐다 — 오프셋을 그대로 해석해야 한다.
        assertThat(RssClient.parseDate("2026-10-08T07:14:00-04:00"))
                .isEqualTo(LocalDateTime.of(2026, 10, 8, 20, 14));
    }

    @Test
    void 날짜가_넘어가는_경우도_정확하다() {
        // 10-08 20:00 UTC = 10-09 05:00 KST — 일자 집계가 하루 밀리던 지점.
        assertThat(RssClient.parseDate("Thu, 08 Oct 2026 20:00:00 GMT"))
                .isEqualTo(LocalDateTime.of(2026, 10, 9, 5, 0));
    }

    @Test
    void 파싱불가_입력은_null() {
        assertThat(RssClient.parseDate(null)).isNull();
        assertThat(RssClient.parseDate("")).isNull();
        assertThat(RssClient.parseDate("   ")).isNull();
        assertThat(RssClient.parseDate("어제")).isNull();
    }
}
