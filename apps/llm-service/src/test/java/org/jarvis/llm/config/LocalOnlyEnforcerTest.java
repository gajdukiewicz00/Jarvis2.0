package org.jarvis.llm.config;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LocalOnlyEnforcerTest {

    private LocalOnlyEnforcer enforcer(boolean localOnly, String llmBaseUrl, String memoryUrl,
            String userProfileUrl, String hostDaemonHost) {
        LocalOnlyEnforcer enforcer = new LocalOnlyEnforcer();
        ReflectionTestUtils.setField(enforcer, "localOnly", localOnly);
        ReflectionTestUtils.setField(enforcer, "llmBaseUrl", llmBaseUrl);
        ReflectionTestUtils.setField(enforcer, "memoryServiceUrl", memoryUrl);
        ReflectionTestUtils.setField(enforcer, "userProfileUrl", userProfileUrl);
        ReflectionTestUtils.setField(enforcer, "hostDaemonHost", hostDaemonHost);
        return enforcer;
    }

    @Test
    void skipsAllChecksWhenLocalOnlyDisabled() {
        LocalOnlyEnforcer enforcer = enforcer(false, "https://api.openai.com/v1", "", "",
                "host-model-daemon.jarvis-prod.svc.cluster.local");

        assertThatCode(enforcer::enforce).doesNotThrowAnyException();
    }

    @Test
    void passesWithDefaultClusterLocalUrls() {
        LocalOnlyEnforcer enforcer = enforcer(true,
                "http://host-model-daemon.jarvis-prod.svc.cluster.local:18080",
                "http://memory-service.jarvis-prod.svc.cluster.local:8093",
                "http://user-profile.jarvis-prod.svc.cluster.local:8089",
                "host-model-daemon.jarvis-prod.svc.cluster.local");

        assertThatCode(enforcer::enforce).doesNotThrowAnyException();
    }

    @Test
    void allowsBlankUrlsToBeIgnored() {
        LocalOnlyEnforcer enforcer = enforcer(true, "", "", "",
                "host-model-daemon.jarvis-prod.svc.cluster.local");

        assertThatCode(enforcer::enforce).doesNotThrowAnyException();
    }

    @Test
    void allowsLocalhostAndPrivateIps() {
        LocalOnlyEnforcer enforcer = enforcer(true, "http://localhost:5000",
                "http://10.0.0.5:8093", "http://192.168.1.20:8089",
                "host-model-daemon.jarvis-prod.svc.cluster.local");

        assertThatCode(enforcer::enforce).doesNotThrowAnyException();
    }

    @Test
    void allowsBareServiceHostnameWithoutDot() {
        LocalOnlyEnforcer enforcer = enforcer(true, "http://host-model-daemon:18080", "", "",
                "host-model-daemon");

        assertThatCode(enforcer::enforce).doesNotThrowAnyException();
    }

    @Test
    void refusesCloudProviderUrl() {
        LocalOnlyEnforcer enforcer = enforcer(true, "https://api.openai.com/v1/chat/completions", "", "",
                "host-model-daemon.jarvis-prod.svc.cluster.local");

        assertThatThrownBy(enforcer::enforce)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cloud LLM provider");
    }

    @Test
    void refusesAnthropicCloudUrlForMemoryService() {
        LocalOnlyEnforcer enforcer = enforcer(true, "", "https://api.anthropic.com/v1", "",
                "host-model-daemon.jarvis-prod.svc.cluster.local");

        assertThatThrownBy(enforcer::enforce)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cloud LLM provider");
    }

    @Test
    void refusesNonLocalHostForUserProfile() {
        LocalOnlyEnforcer enforcer = enforcer(true, "", "", "http://evil.example.com:8089",
                "host-model-daemon.jarvis-prod.svc.cluster.local");

        assertThatThrownBy(enforcer::enforce)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("resolves to a non-local host");
    }

    @Test
    void refusesNonLocalHostDaemonHost() {
        LocalOnlyEnforcer enforcer = enforcer(true, "", "", "",
                "attacker.example.com");

        assertThatThrownBy(enforcer::enforce)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("resolves to a non-local host");
    }

    @Test
    void ignoresValueThatIsNotAValidUrl() {
        LocalOnlyEnforcer enforcer = enforcer(true, "not a url at all", "", "",
                "host-model-daemon.jarvis-prod.svc.cluster.local");

        assertThatCode(enforcer::enforce).doesNotThrowAnyException();
    }
}
