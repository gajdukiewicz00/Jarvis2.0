package org.jarvis.lifetracker.config;

import org.jarvis.common.security.DevSecurityConfig;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;

@Configuration
@EnableWebSecurity
@Profile("dev")
public class LifeTrackerDevSecurityConfig extends DevSecurityConfig {
    public LifeTrackerDevSecurityConfig() {
        super("Life-Tracker");
    }
}
