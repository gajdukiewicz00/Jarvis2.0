package org.jarvis.security.model;

/**
 * Canonical role values for {@link User#getRole()}.
 *
 * <p>The {@code users.role} column remains a plain string for backward
 * compatibility with existing data, migrations, and the JWT {@code role}
 * claim. This enum centralizes the set of values the system recognizes and
 * validates against, adding OWNER / GUEST / SERVICE alongside the original
 * USER / ADMIN pair.</p>
 */
public enum Role {

    /** Highest-privilege human operator: bootstrap admin, session/audit administration. */
    OWNER,
    /** Administrative operator, below OWNER. */
    ADMIN,
    /** Standard authenticated end user (default for self-service registration). */
    USER,
    /** Restricted, read-mostly access for temporary or limited visitors. */
    GUEST,
    /** Non-human / machine-to-machine caller (internal services, automation). */
    SERVICE;

    /**
     * @return true if {@code value} matches one of this enum's names, case-insensitively.
     */
    public static boolean isValid(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        String trimmed = value.trim();
        for (Role role : values()) {
            if (role.name().equalsIgnoreCase(trimmed)) {
                return true;
            }
        }
        return false;
    }

    /**
     * @return true if {@code roleValue} (as stored on a {@link User}) denotes {@code expected}.
     */
    public static boolean matches(String roleValue, Role expected) {
        return expected.name().equalsIgnoreCase(roleValue);
    }
}
