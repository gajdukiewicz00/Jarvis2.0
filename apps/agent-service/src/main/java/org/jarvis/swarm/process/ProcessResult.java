package org.jarvis.swarm.process;

/** Result of a TESTER process run. */
public record ProcessResult(int exitCode, String output, boolean timedOut) {

    public boolean isSuccess() {
        return !timedOut && exitCode == 0;
    }
}
