package org.jarvis.planner;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class PlannerServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(PlannerServiceApplication.class, args);
    }
}
