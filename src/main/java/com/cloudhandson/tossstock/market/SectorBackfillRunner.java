package com.cloudhandson.tossstock.market;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;

/** 기동(default 프로파일) 시 sector 가 비어 있으면 분류해서 UNIVERSE.sector 채움. 멱등. */
@Component
@Profile("default")
@Order(10)  // UniverseSeeder(@Order 기본) 이후
public class SectorBackfillRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SectorBackfillRunner.class);

    private final UniverseMapper mapper;
    private final SectorClassifier classifier;

    public SectorBackfillRunner(UniverseMapper mapper, SectorClassifier classifier) {
        this.mapper = mapper;
        this.classifier = classifier;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (mapper.countWithoutSector() == 0) {
            return;
        }
        List<Universe> all = mapper.findAll();
        int n = 0;
        for (Universe u : all) {
            if (u.getSector() == null) {
                mapper.updateSector(u.getSymbol(), classifier.classify(u.getName(), u.getKsic(), u.getProduct()));
                n++;
            }
        }
        log.info("섹터 분류 백필 완료: {} 종목", n);
    }
}
