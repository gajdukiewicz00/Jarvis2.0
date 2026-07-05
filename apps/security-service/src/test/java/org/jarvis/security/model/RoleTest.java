package org.jarvis.security.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RoleTest {

    @Test
    void isValidAcceptsAllCanonicalRoleNamesCaseInsensitively() {
        assertThat(Role.isValid("OWNER")).isTrue();
        assertThat(Role.isValid("admin")).isTrue();
        assertThat(Role.isValid("User")).isTrue();
        assertThat(Role.isValid("guest")).isTrue();
        assertThat(Role.isValid("SERVICE")).isTrue();
    }

    @Test
    void isValidTrimsSurroundingWhitespace() {
        assertThat(Role.isValid("  OWNER  ")).isTrue();
    }

    @Test
    void isValidRejectsUnknownOrBlankValues() {
        assertThat(Role.isValid("SUPERUSER")).isFalse();
        assertThat(Role.isValid("")).isFalse();
        assertThat(Role.isValid("   ")).isFalse();
        assertThat(Role.isValid(null)).isFalse();
    }

    @Test
    void matchesComparesCaseInsensitively() {
        assertThat(Role.matches("owner", Role.OWNER)).isTrue();
        assertThat(Role.matches("OWNER", Role.OWNER)).isTrue();
        assertThat(Role.matches("admin", Role.OWNER)).isFalse();
    }

    @Test
    void enumContainsExactlyTheFiveCanonicalRoles() {
        assertThat(Role.values()).containsExactly(
                Role.OWNER, Role.ADMIN, Role.USER, Role.GUEST, Role.SERVICE);
    }
}
