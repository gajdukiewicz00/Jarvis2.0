package org.jarvis.pccontrol.controller;

import org.jarvis.pccontrol.service.FileService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FileControllerTest {

    @Mock
    private FileService fileService;

    @InjectMocks
    private FileController controller;

    @Test
    void listFilesReturnsOkWithFileList() throws IOException {
        when(fileService.listFiles("docs")).thenReturn(List.of("a.txt", "b.txt"));

        ResponseEntity<List<String>> response = controller.listFiles("docs");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(List.of("a.txt", "b.txt"), response.getBody());
    }

    @Test
    void listFilesReturnsForbiddenOnSecurityException() throws IOException {
        doThrow(new SecurityException("escape")).when(fileService).listFiles("../etc");

        ResponseEntity<List<String>> response = controller.listFiles("../etc");

        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
    }

    @Test
    void listFilesReturnsBadRequestOnIllegalArgumentException() throws IOException {
        doThrow(new IllegalArgumentException("missing")).when(fileService).listFiles("missing");

        ResponseEntity<List<String>> response = controller.listFiles("missing");

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    }

    @Test
    void listFilesReturnsBadRequestOnIOException() throws IOException {
        doThrow(new IOException("disk error")).when(fileService).listFiles("broken");

        ResponseEntity<List<String>> response = controller.listFiles("broken");

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    }

    @Test
    void readFileReturnsOkWithContent() throws IOException {
        when(fileService.readFile("notes.txt")).thenReturn("hello world");

        ResponseEntity<String> response = controller.readFile("notes.txt");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("hello world", response.getBody());
    }

    @Test
    void readFileReturnsForbiddenWithBodyOnSecurityException() throws IOException {
        doThrow(new SecurityException("blocked extension")).when(fileService).readFile("secret.bin");

        ResponseEntity<String> response = controller.readFile("secret.bin");

        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
        assertEquals("Access denied", response.getBody());
    }

    @Test
    void readFileReturnsBadRequestWithMessageOnIllegalArgumentException() throws IOException {
        doThrow(new IllegalArgumentException("no such file")).when(fileService).readFile("ghost.txt");

        ResponseEntity<String> response = controller.readFile("ghost.txt");

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertTrue(response.getBody().contains("no such file"));
    }

    @Test
    void readFileReturnsBadRequestWithMessageOnIOException() throws IOException {
        doThrow(new IOException("disk error")).when(fileService).readFile("broken.txt");

        ResponseEntity<String> response = controller.readFile("broken.txt");

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertTrue(response.getBody().contains("disk error"));
    }

    @Test
    void getFileInfoReturnsOkWithMetadata() throws IOException {
        when(fileService.getFileInfo("notes.txt")).thenReturn(Map.of("name", "notes.txt", "size", 42L));

        ResponseEntity<Map<String, Object>> response = controller.getFileInfo("notes.txt");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("notes.txt", response.getBody().get("name"));
    }

    @Test
    void getFileInfoReturnsForbiddenOnSecurityException() throws IOException {
        doThrow(new SecurityException("escape")).when(fileService).getFileInfo("../etc/passwd");

        ResponseEntity<Map<String, Object>> response = controller.getFileInfo("../etc/passwd");

        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
    }

    @Test
    void getFileInfoReturnsBadRequestOnIllegalArgumentException() throws IOException {
        doThrow(new IllegalArgumentException("missing")).when(fileService).getFileInfo("missing");

        ResponseEntity<Map<String, Object>> response = controller.getFileInfo("missing");

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    }

    @Test
    void getFileInfoReturnsBadRequestOnIOException() throws IOException {
        doThrow(new IOException("disk error")).when(fileService).getFileInfo("broken");

        ResponseEntity<Map<String, Object>> response = controller.getFileInfo("broken");

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    }
}
