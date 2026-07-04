package org.jarvis.media.workspace;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.jarvis.media.config.MediaProperties;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Owns the on-disk media workspace and enforces the file-path trust boundary.
 *
 * <p>Two distinct operations:</p>
 * <ul>
 *   <li>{@link #resolveInWorkspace(String)} — where the service is allowed to WRITE
 *       artifacts. Always under the single writable workspace directory.</li>
 *   <li>{@link #validateInputPath(String)} — where the service is allowed to READ
 *       source media. Must resolve inside the workspace or one of the configured
 *       read-only input roots.</li>
 * </ul>
 *
 * <p>All validation rejects {@code ..} traversal, null bytes, blank values, and any
 * path that — after normalization to an absolute real path — escapes its allowed
 * root. This is the single chokepoint every job goes through before touching disk.</p>
 */
@Slf4j
@Component
public class WorkspaceManager {

    private final Path workspace;
    private final List<Path> inputRoots;

    public WorkspaceManager(MediaProperties props) {
        this.workspace = Path.of(props.workspace().dir()).toAbsolutePath().normalize();
        List<Path> roots = new ArrayList<>();
        roots.add(workspace);
        String configured = props.workspace().inputRoots();
        if (configured != null && !configured.isBlank()) {
            for (String part : configured.split(":")) {
                if (!part.isBlank()) {
                    roots.add(Path.of(part.trim()).toAbsolutePath().normalize());
                }
            }
        }
        this.inputRoots = List.copyOf(roots);
    }

    @PostConstruct
    public void init() {
        try {
            Files.createDirectories(workspace);
            log.info("Media workspace ready at {} (input roots: {})", workspace, inputRoots);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot create media workspace at " + workspace, e);
        }
    }

    public Path workspaceDir() {
        return workspace;
    }

    /** A fresh unique sub-directory name for one job's artifacts. */
    public String newWorkId() {
        return java.util.UUID.randomUUID().toString();
    }

    /** File size in bytes, or 0 if the file is missing/unreadable (mock mode). */
    public long sizeOrZero(Path path) {
        try {
            return Files.isRegularFile(path) ? Files.size(path) : 0L;
        } catch (IOException e) {
            return 0L;
        }
    }

    /**
     * Resolve a relative artifact name to an absolute path INSIDE the workspace,
     * creating parent directories. Rejects any name that escapes the workspace.
     */
    public Path resolveInWorkspace(String relativeName) {
        String safe = requireSane(relativeName, "artifact name");
        if (Path.of(safe).isAbsolute()) {
            throw new PathValidationException("Artifact name must be relative: " + relativeName);
        }
        Path resolved = workspace.resolve(safe).toAbsolutePath().normalize();
        if (!isWithin(workspace, resolved)) {
            throw new PathValidationException("Artifact path escapes workspace: " + relativeName);
        }
        try {
            Files.createDirectories(resolved.getParent());
        } catch (IOException e) {
            throw new IllegalStateException("Cannot create artifact directory for " + resolved, e);
        }
        return resolved;
    }

    /**
     * Validate a supplied input media path. Must resolve within the workspace or a
     * configured input root. Does not require the file to exist (mock providers
     * operate on logical paths); existence is checked by real providers when needed.
     */
    public Path validateInputPath(String rawPath) {
        String safe = requireSane(rawPath, "input path");
        Path candidate = Path.of(safe).toAbsolutePath().normalize();
        for (Path root : inputRoots) {
            if (isWithin(root, candidate)) {
                return candidate;
            }
        }
        throw new PathValidationException("Input path is outside allowed roots: " + rawPath);
    }

    private String requireSane(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new PathValidationException(label + " must not be blank");
        }
        if (value.indexOf('\0') >= 0) {
            throw new PathValidationException(label + " contains a null byte");
        }
        // Explicitly reject traversal tokens even before normalization for defence-in-depth.
        for (String segment : value.split("[/\\\\]")) {
            if (segment.equals("..")) {
                throw new PathValidationException(label + " contains a parent-directory traversal: " + value);
            }
        }
        return value;
    }

    private boolean isWithin(Path base, Path candidate) {
        return candidate.equals(base) || candidate.startsWith(base);
    }
}
