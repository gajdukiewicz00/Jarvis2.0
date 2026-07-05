package org.jarvis.memory.exception;

/**
 * Roadmap P1 #9 — thrown on ingest when {@code jarvis.memory.dedup.strategy}
 * is {@code REJECT} and a content-identical ACTIVE note already exists.
 */
public class DuplicateMemoryException extends RuntimeException {

    private final String existingMemoryId;

    public DuplicateMemoryException(String existingMemoryId) {
        super("a near-identical memory note already exists: " + existingMemoryId);
        this.existingMemoryId = existingMemoryId;
    }

    public String getExistingMemoryId() {
        return existingMemoryId;
    }
}
