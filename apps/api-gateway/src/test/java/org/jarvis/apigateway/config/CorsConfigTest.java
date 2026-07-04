package org.jarvis.apigateway.config;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CorsConfigTest {

    private CorsConfig newConfig(boolean enabled, List<String> origins, boolean allowCredentials) {
        CorsConfig config = new CorsConfig();
        ReflectionTestUtils.setField(config, "corsEnabled", enabled);
        ReflectionTestUtils.setField(config, "allowedOrigins", origins);
        ReflectionTestUtils.setField(config, "allowedMethods", List.of("GET", "POST"));
        ReflectionTestUtils.setField(config, "allowedHeaders", List.of("*"));
        ReflectionTestUtils.setField(config, "allowCredentials", allowCredentials);
        return config;
    }

    @Test
    void corsDisabledReturnsEmptyConfigurationSource() {
        CorsConfig config = newConfig(false, List.of("https://app.example.com"), false);

        CorsConfigurationSource source = config.corsConfigurationSource();

        assertThat(source).isInstanceOf(UrlBasedCorsConfigurationSource.class);
        assertThat(((UrlBasedCorsConfigurationSource) source).getCorsConfigurations()).isEmpty();
    }

    @Test
    void corsEnabledWithNoOriginsReturnsEmptyConfigurationSource() {
        CorsConfig config = newConfig(true, List.of(""), false);

        CorsConfigurationSource source = config.corsConfigurationSource();

        assertThat(((UrlBasedCorsConfigurationSource) source).getCorsConfigurations()).isEmpty();
    }

    @Test
    void corsEnabledWithWildcardAndCredentialsThrows() {
        CorsConfig config = newConfig(true, List.of("*"), true);

        assertThatThrownBy(config::corsConfigurationSource)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("wildcard");
    }

    @Test
    void corsEnabledRegistersNormalizedOriginsAndSettings() {
        CorsConfig config = newConfig(true, List.of(" https://app.example.com ", "", "https://admin.example.com"), true);

        UrlBasedCorsConfigurationSource source =
                (UrlBasedCorsConfigurationSource) config.corsConfigurationSource();

        CorsConfiguration registered = source.getCorsConfigurations().get("/**");
        assertThat(registered).isNotNull();
        assertThat(registered.getAllowedOriginPatterns())
                .containsExactly("https://app.example.com", "https://admin.example.com");
        assertThat(registered.getAllowedMethods()).containsExactly("GET", "POST");
        assertThat(registered.getAllowCredentials()).isTrue();
        assertThat(registered.getMaxAge()).isEqualTo(3600L);
    }
}
