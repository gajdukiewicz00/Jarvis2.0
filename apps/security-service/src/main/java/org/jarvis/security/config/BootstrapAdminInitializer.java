package org.jarvis.security.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jarvis.security.service.AuthService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.Set;

@Slf4j
@Component
@RequiredArgsConstructor
public class BootstrapAdminInitializer implements ApplicationRunner {

    private static final Set<String> WEAK_PASSWORDS = Set.of(
            "admin",
            "admin123",
            "password",
            "changeme",
            "qwerty");

    private final AuthService authService;

    @Value("${jarvis.bootstrap-admin.enabled:false}")
    private boolean enabled;

    @Value("${jarvis.bootstrap-admin.username:}")
    private String username;

    @Value("${jarvis.bootstrap-admin.password:}")
    private String password;

    @Value("${jarvis.bootstrap-admin.role:ADMIN}")
    private String role;

    @Override
    public void run(ApplicationArguments args) {
        if (!enabled) {
            return;
        }

        if (username == null || username.isBlank()) {
            throw new IllegalStateException("jarvis.bootstrap-admin.username must be provided");
        }
        if (password == null || password.isBlank()) {
            throw new IllegalStateException("jarvis.bootstrap-admin.password must be provided");
        }

        String normalizedPassword = password.trim().toLowerCase();
        if (password.length() < 12 || WEAK_PASSWORDS.contains(normalizedPassword)) {
            throw new IllegalStateException(
                    "jarvis.bootstrap-admin.password must be strong (>=12 chars, non-default)");
        }

        authService.ensureBootstrapAdmin(username.trim(), password, role);
    }
}
