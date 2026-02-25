package org.jarvis.security.config;

import org.jarvis.security.service.AuthService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class BootstrapAdminInitializerTest {

    @Test
    void runDoesNothingWhenDisabled() throws Exception {
        AuthService authService = mock(AuthService.class);
        BootstrapAdminInitializer initializer = new BootstrapAdminInitializer(authService);
        ReflectionTestUtils.setField(initializer, "enabled", false);

        initializer.run(new DefaultApplicationArguments(new String[0]));

        verifyNoInteractions(authService);
    }

    @Test
    void runFailsForWeakPassword() {
        AuthService authService = mock(AuthService.class);
        BootstrapAdminInitializer initializer = new BootstrapAdminInitializer(authService);
        ReflectionTestUtils.setField(initializer, "enabled", true);
        ReflectionTestUtils.setField(initializer, "username", "admin");
        ReflectionTestUtils.setField(initializer, "password", "admin123");
        ReflectionTestUtils.setField(initializer, "role", "ADMIN");

        assertThrows(IllegalStateException.class,
                () -> initializer.run(new DefaultApplicationArguments(new String[0])));
        verifyNoInteractions(authService);
    }

    @Test
    void runCreatesBootstrapAdminWhenConfigIsValid() throws Exception {
        AuthService authService = mock(AuthService.class);
        BootstrapAdminInitializer initializer = new BootstrapAdminInitializer(authService);
        ReflectionTestUtils.setField(initializer, "enabled", true);
        ReflectionTestUtils.setField(initializer, "username", "bootstrap-admin");
        ReflectionTestUtils.setField(initializer, "password", "strong-password-123");
        ReflectionTestUtils.setField(initializer, "role", "ADMIN");

        initializer.run(new DefaultApplicationArguments(new String[0]));

        verify(authService).ensureBootstrapAdmin("bootstrap-admin", "strong-password-123", "ADMIN");
    }
}
