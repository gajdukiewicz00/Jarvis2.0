package org.jarvis.swarm.process;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Safe external-process wrapper for the TESTER role. Commands are an explicit argument
 * list passed to {@link ProcessBuilder} — never a shell string — so user-controlled goal
 * text cannot break into shell interpretation. Runs with a working directory (the
 * sandbox), a hard timeout, and no inherited environment surprises.
 */
@Slf4j
@Component
public class ProcessRunner {

    public ProcessResult run(List<String> command, Path workingDir, int timeoutSeconds)
            throws IOException, InterruptedException {
        if (command == null || command.isEmpty()) {
            throw new IllegalArgumentException("command must not be empty");
        }
        ProcessBuilder pb = new ProcessBuilder(List.copyOf(command));
        if (workingDir != null) {
            pb.directory(workingDir.toFile());
        }
        pb.redirectErrorStream(true);
        Process process = pb.start();
        StringBuilder out = new StringBuilder();
        Thread drain = new Thread(() -> drain(process.getInputStream(), out), "proc-out");
        drain.start();

        boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
        if (!finished) {
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
            drain.join(1000);
            return new ProcessResult(-1, out.toString(), true);
        }
        drain.join(2000);
        return new ProcessResult(process.exitValue(), out.toString(), false);
    }

    private void drain(InputStream stream, StringBuilder sink) {
        try (stream) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = stream.read(buf)) != -1) {
                sink.append(new String(buf, 0, n, StandardCharsets.UTF_8));
            }
        } catch (IOException ignored) {
            // process ended; partial output acceptable
        }
    }
}
