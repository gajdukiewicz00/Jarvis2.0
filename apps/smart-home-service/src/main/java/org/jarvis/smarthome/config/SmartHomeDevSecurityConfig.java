package org.jarvis.smarthome.config;

import org.jarvis.common.security.DevSecurityConfig;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;

@Configuration
@EnableWebSecurity
@Profile("dev")
public class SmartHomeDevSecurityConfig extends DevSecurityConfig {
    public SmartHomeDevSecurityConfig() {
        super("Smart-Home-Service");
    }
}
