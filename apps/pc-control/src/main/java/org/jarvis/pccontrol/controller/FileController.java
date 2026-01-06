package org.jarvis.pccontrol.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jarvis.pccontrol.service.FileService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/v1/pc/files")
@RequiredArgsConstructor
public class FileController {

    private final FileService fileService;

    @GetMapping("/list")
    public ResponseEntity<List<String>> listFiles(@RequestParam String path) {
        try {
            return ResponseEntity.ok(fileService.listFiles(path));
        } catch (Exception e) {
            log.error("Error listing files in path: {}", path, e);
            return ResponseEntity.badRequest().build();
        }
    }

    @GetMapping("/content")
    public ResponseEntity<String> readFile(@RequestParam String path) {
        try {
            return ResponseEntity.ok(fileService.readFile(path));
        } catch (Exception e) {
            log.error("Error reading file: {}", path, e);
            return ResponseEntity.badRequest().body("Error reading file: " + e.getMessage());
        }
    }

    @GetMapping("/info")
    public ResponseEntity<Map<String, Object>> getFileInfo(@RequestParam String path) {
        try {
            return ResponseEntity.ok(fileService.getFileInfo(path));
        } catch (Exception e) {
            log.error("Error getting file info: {}", path, e);
            return ResponseEntity.badRequest().build();
        }
    }
}
