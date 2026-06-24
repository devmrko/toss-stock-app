package com.cloudhandson.tossstock.broker;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;

/** 기동 시 매매처가 비어있으면 국내 주요 증권사로 시드(멱등). 스키마 생성 후 실행. (#442) */
@Component
@Profile("default")
@Order(30)
public class BrokerSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(BrokerSeeder.class);

    private static final List<String> DEFAULTS = List.of(
            "토스증권", "키움증권", "미래에셋증권", "삼성증권", "NH투자증권", "KB증권",
            "한국투자증권", "신한투자증권", "하나증권", "메리츠증권", "대신증권", "유안타증권",
            "한화투자증권", "카카오페이증권", "LS증권", "현대차증권", "SK증권", "교보증권",
            "IBK투자증권", "DB금융투자");

    private final BrokerMapper mapper;

    public BrokerSeeder(BrokerMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (mapper.count() > 0) {
            return;
        }
        DEFAULTS.forEach(mapper::merge);
        log.info("매매처 시드 완료: {}개", DEFAULTS.size());
    }
}
