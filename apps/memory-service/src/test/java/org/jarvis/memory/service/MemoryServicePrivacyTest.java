package org.jarvis.memory.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * B2 — server-side privacy filter: local_only / sensitive chunks are excluded
 * unless the active provider is permitted to receive them.
 */
class MemoryServicePrivacyTest {

    @Test
    void localOnlyRequiresIncludeLocalOnly() {
        assertThat(MemoryService.privacyAllowed("local_only", true, true)).isTrue();
        assertThat(MemoryService.privacyAllowed("local_only", false, true)).isFalse();
        assertThat(MemoryService.privacyAllowed("local-only", false, true)).isFalse(); // hyphen variant
    }

    @Test
    void sensitiveRequiresIncludeSensitive() {
        assertThat(MemoryService.privacyAllowed("sensitive", true, false)).isFalse();
        assertThat(MemoryService.privacyAllowed("sensitive", true, true)).isTrue();
    }

    @Test
    void publicPrivateAndNullAlwaysAllowed() {
        assertThat(MemoryService.privacyAllowed("public", false, false)).isTrue();
        assertThat(MemoryService.privacyAllowed("private", false, false)).isTrue();
        assertThat(MemoryService.privacyAllowed(null, false, false)).isTrue();
        assertThat(MemoryService.privacyAllowed("", false, false)).isTrue();
    }
}
