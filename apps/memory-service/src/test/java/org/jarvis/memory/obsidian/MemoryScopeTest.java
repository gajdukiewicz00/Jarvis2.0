package org.jarvis.memory.obsidian;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MemoryScopeTest {

    @Test
    void fromStringParsesKnownValuesCaseInsensitively() {
        assertThat(MemoryScope.fromString("finance")).isEqualTo(MemoryScope.FINANCE);
        assertThat(MemoryScope.fromString("HEALTH")).isEqualTo(MemoryScope.HEALTH);
        assertThat(MemoryScope.fromString("Temporary")).isEqualTo(MemoryScope.TEMPORARY);
    }

    @Test
    void fromStringDefaultsToUserProfileWhenNullBlankOrUnknown() {
        assertThat(MemoryScope.fromString(null)).isEqualTo(MemoryScope.USER_PROFILE);
        assertThat(MemoryScope.fromString("   ")).isEqualTo(MemoryScope.USER_PROFILE);
        assertThat(MemoryScope.fromString("not-a-scope")).isEqualTo(MemoryScope.USER_PROFILE);
    }
}
