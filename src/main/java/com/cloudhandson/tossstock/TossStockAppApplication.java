package com.cloudhandson.tossstock;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class TossStockAppApplication {
    public static void main(String[] args) {
        SpringApplication.run(TossStockAppApplication.class, args);
    }
}
