package org.jarvis.pccontrol.service.impl;

import lombok.extern.slf4j.Slf4j;
import org.jarvis.pccontrol.service.FileService;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Slf4j
@Service
public class LinuxFileService implements FileService {

    @Override
    public List<String> listFiles(String pathStr) throws Exception {
        Path path = Paths.get(pathStr);
        if (!Files.exists(path)) {
            throw new IllegalArgumentException("Path does not exist: " + pathStr);
        }
        if (!Files.isDirectory(path)) {
            throw new IllegalArgumentException("Path is not a directory: " + pathStr);
        }

        try (Stream<Path> stream = Files.list(path)) {
            return stream
                    .map(p -> p.getFileName().toString() + (Files.isDirectory(p) ? "/" : ""))
                    .collect(Collectors.toList());
        }
    }

    @Override
    public String readFile(String pathStr) throws Exception {
        Path path = Paths.get(pathStr);
        if (!Files.exists(path)) {
            throw new IllegalArgumentException("File does not exist: " + pathStr);
        }
        if (Files.isDirectory(path)) {
            throw new IllegalArgumentException("Cannot read a directory: " + pathStr);
        }
        // Basic check to avoid reading huge files
        if (Files.size(path) > 10_000_000) { // 10MB limit
            throw new IllegalArgumentException("File is too large to read: " + pathStr);
        }

        return Files.readString(path);
    }

    @Override
    public Map<String, Object> getFileInfo(String pathStr) throws Exception {
        Path path = Paths.get(pathStr);
        if (!Files.exists(path)) {
            throw new IllegalArgumentException("Path does not exist: " + pathStr);
        }

        BasicFileAttributes attrs = Files.readAttributes(path, BasicFileAttributes.class);
        Map<String, Object> info = new HashMap<>();
        info.put("name", path.getFileName().toString());
        info.put("path", path.toAbsolutePath().toString());
        info.put("isDirectory", attrs.isDirectory());
        info.put("size", attrs.size());
        info.put("lastModified", attrs.lastModifiedTime().toString());
        return info;
    }
}
