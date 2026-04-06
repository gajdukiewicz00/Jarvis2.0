package org.jarvis.memory.exception;

public class MemoryDependencyUnavailableException extends RuntimeException {

    private final String dependency;

    public MemoryDependencyUnavailableException(String dependency, String message, Throwable cause) {
        super(message, cause);
        this.dependency = dependency;
    }

    public String getDependency() {
        return dependency;
    }
}
