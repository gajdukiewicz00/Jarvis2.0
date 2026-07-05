package org.jarvis.memory.obsidian;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ImportConflictModeTest {

    @Test
    void fromStringParsesKnownValuesCaseInsensitively() {
        assertThat(ImportConflictMode.fromString("skip")).isEqualTo(ImportConflictMode.SKIP);
        assertThat(ImportConflictMode.fromString("OVERWRITE")).isEqualTo(ImportConflictMode.OVERWRITE);
        assertThat(ImportConflictMode.fromString("Overwrite")).isEqualTo(ImportConflictMode.OVERWRITE);
    }

    @Test
    void fromStringAcceptsHyphenAndSpaceSeparatedKeepBoth() {
        assertThat(ImportConflictMode.fromString("keep-both")).isEqualTo(ImportConflictMode.KEEP_BOTH);
        assertThat(ImportConflictMode.fromString("keep both")).isEqualTo(ImportConflictMode.KEEP_BOTH);
        assertThat(ImportConflictMode.fromString("KEEP_BOTH")).isEqualTo(ImportConflictMode.KEEP_BOTH);
    }

    @Test
    void fromStringDefaultsToSkipWhenNullBlankOrUnknown() {
        assertThat(ImportConflictMode.fromString(null)).isEqualTo(ImportConflictMode.SKIP);
        assertThat(ImportConflictMode.fromString("   ")).isEqualTo(ImportConflictMode.SKIP);
        assertThat(ImportConflictMode.fromString("not-a-mode")).isEqualTo(ImportConflictMode.SKIP);
    }
}
