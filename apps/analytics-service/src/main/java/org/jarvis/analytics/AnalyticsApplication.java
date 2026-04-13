package org.jarvis.analytics;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.cloud.openfeign.EnableFeignClients;

import java.time.Clock;

@SpringBootApplication
@EnableFeignClients
public class AnalyticsApplication {
    public static void main(String[] args) {
        SpringApplication.run(AnalyticsApplication.class, args);
    }

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
