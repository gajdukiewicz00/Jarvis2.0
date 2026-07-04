package org.jarvis.visionsecurity.config;

import org.jarvis.common.security.BaseSecurityConfig;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;

import java.util.Arrays;

@Configuration
@EnableWebSecurity
@Profile("!dev")
public class SecurityConfig extends BaseSecurityConfig {

    /**
     * Expose the live camera preview without a JWT so a host browser/ffplay can open it.
     * The {@link org.jarvis.visionsecurity.controller.PreviewController} itself rejects any
     * non-loopback caller, so this does not expose the camera to the LAN or to gateway pods.
     */
    @Override
    protected String[] getPublicEndpoints() {
        String[] base = super.getPublicEndpoints();
        String[] extended = Arrays.copyOf(base, base.length + 1);
        extended[base.length] = "/api/v1/vision-security/preview/**";
        return extended;
    }
}
