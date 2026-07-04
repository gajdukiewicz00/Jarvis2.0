package org.jarvis.pccontrol.service.impl;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class LinuxFileServiceTest {
    @TempDir
    Path tempDir;

    @Test
    void listFiles_shouldReturnListOfFiles() throws Exception {
        LinuxFileService fileService = new LinuxFileService(tempDir.toString(), "txt");
        Files.createFile(tempDir.resolve("test1.txt"));
        Files.createDirectory(tempDir.resolve("subdir"));

        List<String> files = fileService.listFiles(".");

        assertTrue(files.contains("test1.txt"));
        assertTrue(files.contains("subdir/"));
    }

    @Test
    void readFile_shouldReturnFileContent() throws Exception {
        LinuxFileService fileService = new LinuxFileService(tempDir.toString(), "txt");
        Path file = tempDir.resolve("content.txt");
        Files.writeString(file, "Hello World");

        String content = fileService.readFile("content.txt");

        assertEquals("Hello World", content);
    }

    @Test
    void getFileInfo_shouldReturnMetadata() throws Exception {
        LinuxFileService fileService = new LinuxFileService(tempDir.toString(), "txt");
        Path file = tempDir.resolve("info.txt");
        Files.writeString(file, "Info");

        Map<String, Object> info = fileService.getFileInfo("info.txt");

        assertEquals("info.txt", info.get("name"));
        assertEquals(file.toAbsolutePath().toString(), info.get("path"));
        assertEquals(false, info.get("isDirectory"));
        assertTrue((long) info.get("size") > 0);
    }

    @Test
    void listFilesThrowsWhenSandboxRootDoesNotExist() {
        LinuxFileService fileService = new LinuxFileService(tempDir.resolve("missing-root").toString(), "txt");

        assertThrows(IllegalArgumentException.class, () -> fileService.listFiles("."));
    }

    @Test
    void listFilesThrowsWhenSandboxRootIsAFile() throws Exception {
        Path rootFile = tempDir.resolve("root-is-a-file.txt");
        Files.writeString(rootFile, "not a directory");
        LinuxFileService fileService = new LinuxFileService(rootFile.toString(), "txt");

        assertThrows(IllegalArgumentException.class, () -> fileService.listFiles("."));
    }

    @Test
    void listFilesThrowsWhenPathIsBlank() {
        LinuxFileService fileService = new LinuxFileService(tempDir.toString(), "txt");

        assertThrows(IllegalArgumentException.class, () -> fileService.listFiles("   "));
    }

    @Test
    void listFilesRejectsAbsolutePaths() {
        LinuxFileService fileService = new LinuxFileService(tempDir.toString(), "txt");

        assertThrows(SecurityException.class, () -> fileService.listFiles("/etc"));
    }

    @Test
    void listFilesRejectsPathsThatEscapeSandbox() {
        LinuxFileService fileService = new LinuxFileService(tempDir.toString(), "txt");

        assertThrows(SecurityException.class, () -> fileService.listFiles("../"));
    }

    @Test
    void listFilesThrowsWhenPathDoesNotExist() {
        LinuxFileService fileService = new LinuxFileService(tempDir.toString(), "txt");

        assertThrows(IllegalArgumentException.class, () -> fileService.listFiles("ghost"));
    }

    @Test
    void listFilesThrowsWhenPathIsNotADirectory() throws Exception {
        Files.writeString(tempDir.resolve("plain.txt"), "content");
        LinuxFileService fileService = new LinuxFileService(tempDir.toString(), "txt");

        assertThrows(IllegalArgumentException.class, () -> fileService.listFiles("plain.txt"));
    }

    @Test
    void listFilesHidesEntriesWithDisallowedExtensions() throws Exception {
        LinuxFileService fileService = new LinuxFileService(tempDir.toString(), "txt");
        Files.createFile(tempDir.resolve("allowed.txt"));
        Files.createFile(tempDir.resolve("blocked.exe"));

        List<String> files = fileService.listFiles(".");

        assertTrue(files.contains("allowed.txt"));
        assertFalse(files.contains("blocked.exe"));
    }

    @Test
    void listFilesAllowsAnyExtensionWhenNoneConfigured() throws Exception {
        LinuxFileService fileService = new LinuxFileService(tempDir.toString(), "");
        Files.createFile(tempDir.resolve("anything.exe"));

        List<String> files = fileService.listFiles(".");

        assertTrue(files.contains("anything.exe"));
    }

    @Test
    void readFileThrowsWhenFileDoesNotExist() {
        LinuxFileService fileService = new LinuxFileService(tempDir.toString(), "txt");

        assertThrows(IllegalArgumentException.class, () -> fileService.readFile("ghost.txt"));
    }

    @Test
    void readFileThrowsWhenPathIsADirectory() throws Exception {
        Files.createDirectory(tempDir.resolve("adir"));
        LinuxFileService fileService = new LinuxFileService(tempDir.toString(), "txt");

        assertThrows(IllegalArgumentException.class, () -> fileService.readFile("adir"));
    }

    @Test
    void readFileRejectsDisallowedExtension() throws Exception {
        Files.writeString(tempDir.resolve("script.sh"), "echo hi");
        LinuxFileService fileService = new LinuxFileService(tempDir.toString(), "txt");

        assertThrows(SecurityException.class, () -> fileService.readFile("script.sh"));
    }

    @Test
    void readFileRejectsFilesLargerThanTenMegabytes() throws Exception {
        Path big = tempDir.resolve("big.txt");
        byte[] chunk = new byte[1024 * 1024];
        try (var out = Files.newOutputStream(big)) {
            for (int i = 0; i < 11; i++) {
                out.write(chunk);
            }
        }
        LinuxFileService fileService = new LinuxFileService(tempDir.toString(), "txt");

        assertThrows(IllegalArgumentException.class, () -> fileService.readFile("big.txt"));
    }

    @Test
    void getFileInfoThrowsWhenPathDoesNotExist() {
        LinuxFileService fileService = new LinuxFileService(tempDir.toString(), "txt");

        assertThrows(IllegalArgumentException.class, () -> fileService.getFileInfo("ghost.txt"));
    }

    @Test
    void getFileInfoAllowsDirectoriesRegardlessOfExtensionAllowList() throws Exception {
        Files.createDirectory(tempDir.resolve("adir"));
        LinuxFileService fileService = new LinuxFileService(tempDir.toString(), "txt");

        Map<String, Object> info = fileService.getFileInfo("adir");

        assertEquals(true, info.get("isDirectory"));
    }

    @Test
    void getFileInfoRejectsDisallowedExtension() throws Exception {
        Files.writeString(tempDir.resolve("script.sh"), "echo hi");
        LinuxFileService fileService = new LinuxFileService(tempDir.toString(), "txt");

        assertThrows(SecurityException.class, () -> fileService.getFileInfo("script.sh"));
    }
}
