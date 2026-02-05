package org.jarvis.pccontrol.service.impl;

import lombok.extern.slf4j.Slf4j;
import org.jarvis.pccontrol.service.FileService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Slf4j
@Service
public class LinuxFileService implements FileService {

    private final Path root;
    private final Set<String> allowedExtensions;

    public LinuxFileService(
            @Value("${pc-control.file-root:/var/jarvis/shared}") String rootPath,
            @Value("${pc-control.file-extensions:txt,log,md,json,yml,yaml,csv}") String extensions) {
        this.root = Paths.get(rootPath).toAbsolutePath().normalize();
        this.allowedExtensions = Arrays.stream(extensions.split(","))
                .map(String::trim)
                .filter(ext -> !ext.isEmpty())
                .map(ext -> ext.startsWith(".") ? ext.toLowerCase(Locale.ROOT) : "." + ext.toLowerCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
    }

    @Override
    public List<String> listFiles(String pathStr) throws Exception {
        ensureRootExists();
        Path path = resolvePath(pathStr);
        if (!Files.exists(path)) {
            throw new IllegalArgumentException("Path does not exist: " + pathStr);
        }
        if (!Files.isDirectory(path)) {
            throw new IllegalArgumentException("Path is not a directory: " + pathStr);
        }

        Path realPath = requireRealPath(path);
        try (Stream<Path> stream = Files.list(realPath)) {
            return stream
                    .filter(this::isSafeEntry)
                    .map(p -> p.getFileName().toString() + (Files.isDirectory(p) ? "/" : ""))
                    .collect(Collectors.toList());
        }
    }

    @Override
    public String readFile(String pathStr) throws Exception {
        ensureRootExists();
        Path path = resolvePath(pathStr);
        if (!Files.exists(path)) {
            throw new IllegalArgumentException("File does not exist: " + pathStr);
        }
        if (Files.isDirectory(path)) {
            throw new IllegalArgumentException("Cannot read a directory: " + pathStr);
        }
        enforceAllowedExtension(path);
        Path realPath = requireRealPath(path);
        // Basic check to avoid reading huge files
        if (Files.size(realPath) > 10_000_000) { // 10MB limit
            throw new IllegalArgumentException("File is too large to read: " + pathStr);
        }

        return Files.readString(realPath);
    }

    @Override
    public Map<String, Object> getFileInfo(String pathStr) throws Exception {
        ensureRootExists();
        Path path = resolvePath(pathStr);
        if (!Files.exists(path)) {
            throw new IllegalArgumentException("Path does not exist: " + pathStr);
        }
        if (!Files.isDirectory(path)) {
            enforceAllowedExtension(path);
        }
        Path realPath = requireRealPath(path);

        BasicFileAttributes attrs = Files.readAttributes(realPath, BasicFileAttributes.class);
        Map<String, Object> info = new HashMap<>();
        info.put("name", realPath.getFileName().toString());
        info.put("path", realPath.toAbsolutePath().toString());
        info.put("isDirectory", attrs.isDirectory());
        info.put("size", attrs.size());
        info.put("lastModified", attrs.lastModifiedTime().toString());
        return info;
    }

    private void ensureRootExists() {
        if (!Files.exists(root)) {
            throw new IllegalArgumentException("Sandbox root does not exist: " + root);
        }
        if (!Files.isDirectory(root)) {
            throw new IllegalArgumentException("Sandbox root is not a directory: " + root);
        }
    }

    private Path resolvePath(String input) {
        if (input == null || input.isBlank()) {
            throw new IllegalArgumentException("Path is required");
        }
        Path requested = Paths.get(input.trim());
        if (requested.isAbsolute()) {
            throw new SecurityException("Absolute paths are not allowed");
        }
        Path resolved = root.resolve(requested).normalize();
        if (!resolved.startsWith(root)) {
            throw new SecurityException("Path escapes sandbox");
        }
        return resolved;
    }

    private Path requireRealPath(Path path) throws IOException {
        Path realRoot = root.toRealPath();
        Path realPath = path.toRealPath();
        if (!realPath.startsWith(realRoot)) {
            throw new SecurityException("Path escapes sandbox");
        }
        return realPath;
    }

    private boolean isSafeEntry(Path entry) {
        try {
            Path realRoot = root.toRealPath();
            Path realEntry = entry.toRealPath();
            if (!realEntry.startsWith(realRoot)) {
                return false;
            }
            if (Files.isDirectory(realEntry)) {
                return true;
            }
            return isAllowedExtension(realEntry);
        } catch (IOException | SecurityException e) {
            log.debug("Skipping unsafe entry {}: {}", entry, e.getMessage());
            return false;
        }
    }

    private void enforceAllowedExtension(Path path) {
        if (!isAllowedExtension(path)) {
            throw new SecurityException("File type not allowed");
        }
    }

    private boolean isAllowedExtension(Path path) {
        if (allowedExtensions.isEmpty()) {
            return true;
        }
        String name = path.getFileName().toString();
        int dotIndex = name.lastIndexOf('.');
        String ext = dotIndex >= 0 ? name.substring(dotIndex).toLowerCase(Locale.ROOT) : "";
        return allowedExtensions.contains(ext);
    }
}
