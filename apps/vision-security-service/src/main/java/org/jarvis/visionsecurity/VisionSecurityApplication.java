package org.jarvis.visionsecurity;

import org.jarvis.visionsecurity.config.VisionSecurityProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(VisionSecurityProperties.class)
public class VisionSecurityApplication {

    public static void main(String[] args) {
        SpringApplication.run(VisionSecurityApplication.class, args);
    }
}
