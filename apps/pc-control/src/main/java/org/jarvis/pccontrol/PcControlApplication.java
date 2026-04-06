package org.jarvis.pccontrol;

import org.jarvis.pccontrol.client.VisionServiceClient;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;

@SpringBootApplication
@EnableFeignClients(basePackageClasses = VisionServiceClient.class)
public class PcControlApplication {
    public static void main(String[] args) {
        SpringApplication.run(PcControlApplication.class, args);
    }
}
