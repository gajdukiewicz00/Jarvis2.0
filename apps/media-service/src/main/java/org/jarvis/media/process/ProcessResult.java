package org.jarvis.media.process;

/** Result of an external process run. */
public record ProcessResult(int exitCode, String stdout, String stderr) {

    public boolean isSuccess() {
        return exitCode == 0;
    }
}
