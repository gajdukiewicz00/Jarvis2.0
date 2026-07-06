package org.jarvis.swarm.process;

/**
 * A single structured test failure/error parsed from test-runner output (see
 * {@link MavenFailureParser}), instead of a raw log dump.
 *
 * @param testClass  the failing test's class name (simple or fully qualified, as reported)
 * @param testMethod the failing test method name
 * @param message    the assertion/error message, if any (empty when the report line carried none)
 */
public record TestFailure(String testClass, String testMethod, String message) {

    public TestFailure {
        message = message == null ? "" : message;
    }

    /** {@code ClassName#methodName}, the compact "where" half of the failure. */
    public String classAndMethod() {
        return testClass + "#" + testMethod;
    }

    /** One-line human description: {@code ClassName#methodName: message}. */
    public String describe() {
        return message.isBlank() ? classAndMethod() : classAndMethod() + ": " + message;
    }
}
