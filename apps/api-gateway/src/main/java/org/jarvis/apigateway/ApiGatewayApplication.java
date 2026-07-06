package org.jarvis.apigateway;

import lombok.extern.slf4j.Slf4j;
import org.jarvis.apigateway.proxy.ProxyTimeoutProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.openfeign.EnableFeignClients;

@Slf4j
@SpringBootApplication
@EnableFeignClients
@EnableConfigurationProperties(ProxyTimeoutProperties.class)
public class ApiGatewayApplication {
    public static void main(String[] args) {
        log.info("🚀 Starting ApiGatewayApplication...");
        SpringApplication.run(ApiGatewayApplication.class, args);
        log.info("✅ ApiGatewayApplication started successfully");
    }
}
