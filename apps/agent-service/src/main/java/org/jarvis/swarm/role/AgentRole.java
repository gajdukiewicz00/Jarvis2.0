package org.jarvis.swarm.role;

/** The seven swarm roles ("House Party Protocol"). */
public enum AgentRole {
    CODER,
    TESTER,
    RESEARCH,
    DOCS,
    SECURITY,
    MEDIA,
    FINANCE;

    public static AgentRole fromText(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("role is required");
        }
        try {
            return AgentRole.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unknown role: " + value);
        }
    }
}
