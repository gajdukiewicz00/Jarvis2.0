package org.jarvis.pccontrol.config;

import org.jarvis.common.security.DevSecurityConfig;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;

/**
 * Development security configuration for PC Control Service.
 * Allows all requests without authentication for easier local development.
 */
@Configuration
@EnableWebSecurity
@Profile("dev")
public class PcControlDevSecurityConfig extends DevSecurityConfig {

    public PcControlDevSecurityConfig() {
        super("PC-Control");
    }
}
