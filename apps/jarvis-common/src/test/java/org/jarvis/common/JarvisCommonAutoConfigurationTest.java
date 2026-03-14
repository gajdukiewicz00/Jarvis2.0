package org.jarvis.common;

import org.jarvis.common.security.ServiceJwtFilter;
import org.jarvis.common.security.ServiceJwtProvider;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.servlet.FilterRegistrationBean;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;

class JarvisCommonAutoConfigurationTest {

    @Test
    void serviceJwtFilterServletRegistrationIsDisabled() {
        JarvisCommonAutoConfiguration configuration = new JarvisCommonAutoConfiguration();
        ServiceJwtProvider provider = new ServiceJwtProvider(
                "0123456789abcdef0123456789abcdef",
                "",
                "jarvis-internal",
                "jarvis-services",
                300,
                true
        );

        ServiceJwtFilter filter = configuration.serviceJwtFilter(provider);
        FilterRegistrationBean<ServiceJwtFilter> registration = configuration.serviceJwtFilterRegistration(filter);

        assertSame(filter, registration.getFilter());
        assertFalse(registration.isEnabled());
    }
}
