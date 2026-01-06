package org.jarvis.pccontrol.service.impl;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class LinuxFileServiceTest {

    private final LinuxFileService fileService = new LinuxFileService();

    @TempDir
    Path tempDir;

    @Test
    void listFiles_shouldReturnListOfFiles() throws Exception {
        Files.createFile(tempDir.resolve("test1.txt"));
        Files.createDirectory(tempDir.resolve("subdir"));

        List<String> files = fileService.listFiles(tempDir.toString());

        assertTrue(files.contains("test1.txt"));
        assertTrue(files.contains("subdir/"));
    }

    @Test
    void readFile_shouldReturnFileContent() throws Exception {
        Path file = tempDir.resolve("content.txt");
        Files.writeString(file, "Hello World");

        String content = fileService.readFile(file.toString());

        assertEquals("Hello World", content);
    }

    @Test
    void getFileInfo_shouldReturnMetadata() throws Exception {
        Path file = tempDir.resolve("info.txt");
        Files.writeString(file, "Info");

        Map<String, Object> info = fileService.getFileInfo(file.toString());

        assertEquals("info.txt", info.get("name"));
        assertEquals(file.toAbsolutePath().toString(), info.get("path"));
        assertEquals(false, info.get("isDirectory"));
        assertTrue((long) info.get("size") > 0);
    }
}
