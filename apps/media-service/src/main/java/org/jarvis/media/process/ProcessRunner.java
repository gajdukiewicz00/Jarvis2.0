package org.jarvis.media.process;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Safe external-process wrapper. Commands are always passed as an explicit argument
 * list to {@link ProcessBuilder} — never a shell string — so a hostile filename can
 * never break out into shell interpretation. The process is run without a shell,
 * with a hard timeout that destroys the process tree on expiry.
 */
@Slf4j
@Component
public class ProcessRunner {

    /**
     * Run a command (argv) with a timeout. {@code command.get(0)} is the binary; the
     * rest are literal arguments. No shell, no string concatenation, no glob expansion.
     */
    public ProcessResult run(List<String> command, int timeoutSeconds) throws IOException, InterruptedException {
        if (command == null || command.isEmpty()) {
            throw new IllegalArgumentException("command must not be empty");
        }
        ProcessBuilder pb = new ProcessBuilder(List.copyOf(command));
        pb.redirectErrorStream(false);
        log.debug("Running process: {} (timeout {}s)", command.get(0), timeoutSeconds);

        Process process = pb.start();
        // Drain stdout/stderr concurrently so a chatty process cannot deadlock on full pipes.
        StreamCollector out = new StreamCollector(process.getInputStream());
        StreamCollector err = new StreamCollector(process.getErrorStream());
        Thread outThread = new Thread(out, "proc-out");
        Thread errThread = new Thread(err, "proc-err");
        outThread.start();
        errThread.start();

        boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
        if (!finished) {
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
            outThread.join(1000);
            errThread.join(1000);
            throw new ProcessTimeoutException("Process timed out after " + timeoutSeconds + "s: " + command.get(0));
        }
        outThread.join(2000);
        errThread.join(2000);
        return new ProcessResult(process.exitValue(), out.text(), err.text());
    }

    private static final class StreamCollector implements Runnable {
        private final InputStream stream;
        private final StringBuilder sb = new StringBuilder();

        StreamCollector(InputStream stream) {
            this.stream = stream;
        }

        @Override
        public void run() {
            try (stream) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = stream.read(buf)) != -1) {
                    sb.append(new String(buf, 0, n, StandardCharsets.UTF_8));
                }
            } catch (IOException ignored) {
                // stream closed on process exit; partial output is acceptable
            }
        }

        String text() {
            return sb.toString();
        }
    }
}
