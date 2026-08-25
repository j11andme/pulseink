package com.pulseink;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication(scanBasePackages = "com.pulseink")
@ConfigurationPropertiesScan("com.pulseink.config")
@MapperScan("com.pulseink.repository")
public class PulseInkApplication {

    public static void main(String[] args) {
        SpringApplication.run(PulseInkApplication.class, args);
    }
}
