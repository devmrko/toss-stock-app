package com.cloudhandson.tossstock.market;

import com.cloudhandson.tossstock.market.Top50Service.SymVol;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(MockitoExtension.class)
class Top50ServiceTest {

    @Mock UniverseMapper universeMapper;
    @Mock VolumeRankWriter writer;
    @Mock com.cloudhandson.tossstock.toss.TossApiClient toss;
    @Mock ScanStatus status;
    @InjectMocks Top50Service service;

    private static SymVol sv(String code, Long vol) {
        return new SymVol(new Universe(code, "name-" + code, "KOSPI", null, null), vol, null);
    }

    @Test
    void rankTop50_excludes_null_volume_and_sorts_desc_capped_at_50() {
        List<SymVol> input = new ArrayList<>();
        input.add(sv("A", 100L));
        input.add(sv("B", null));        // 제외
        input.add(sv("C", 300L));
        input.add(sv("D", 200L));
        for (int i = 0; i < 60; i++) {
            input.add(sv("X" + i, (long) i));   // 0..59
        }

        List<SymVol> top = service.rankTop50(input);

        assertThat(top).hasSize(50);
        assertThat(top.get(0).volume()).isEqualTo(300L);
        assertThat(top.get(1).volume()).isEqualTo(200L);
        assertThat(top.get(2).volume()).isEqualTo(100L);
        // 내림차순 보장
        for (int i = 1; i < top.size(); i++) {
            assertThat(top.get(i - 1).volume()).isGreaterThanOrEqualTo(top.get(i).volume());
        }
        // null 거래량 제외
        assertThat(top).noneMatch(s -> s.u().getSymbol().equals("B"));
    }

    @Test
    void changeRate_computed_and_guards_zero_or_null() {
        assertThat(Top50Service.changeRate(new BigDecimal("352500"), new BigDecimal("350500")))
                .isEqualTo(0.57);
        assertThat(Top50Service.changeRate(null, new BigDecimal("100"))).isNull();
        assertThat(Top50Service.changeRate(new BigDecimal("100"), null)).isNull();
        assertThat(Top50Service.changeRate(new BigDecimal("100"), BigDecimal.ZERO)).isNull();
    }
}
