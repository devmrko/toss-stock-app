package com.cloudhandson.tossstock.market;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.List;
import java.util.Map;

/** 기동(운영 default 프로파일) 시 universe 가 비어 있으면 KRX 시드(JSON)로 채운다. 멱등. */
@Component
@Profile("default")
public class UniverseSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(UniverseSeeder.class);

    private final UniverseMapper mapper;
    private final ObjectMapper objectMapper;

    public UniverseSeeder(UniverseMapper mapper, ObjectMapper objectMapper) {
        this.mapper = mapper;
        this.objectMapper = objectMapper;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        if (mapper.count() > 0) {
            return;
        }
        try (InputStream in = new ClassPathResource("universe-krx.json").getInputStream()) {
            List<Map<String, String>> seed = objectMapper.readValue(in, List.class);
            int n = 0;
            for (Map<String, String> m : seed) {
                mapper.insert(new Universe(m.get("symbol"), m.get("name"), m.get("market")));
                n++;
            }
            log.info("universe 시드 완료: {} 종목", n);
        }
    }
}
