package org.jarvis.llm.client;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class PcControlClient {

    private final RestTemplate restTemplate = new RestTemplate();

    @Value("${app.pc-control.url:http://localhost:8082}")
    private String pcControlUrl;

    public List<String> listFiles(String path) {
        String url = UriComponentsBuilder.fromHttpUrl(pcControlUrl)
                .path("/api/v1/pc/files/list")
                .queryParam("path", path)
                .toUriString();
        
        try {
            return restTemplate.getForObject(url, List.class);
        } catch (Exception e) {
            log.error("Error listing files from pc-control: {}", e.getMessage());
            throw new RuntimeException("Failed to list files: " + e.getMessage());
        }
    }

    public String readFile(String path) {
        String url = UriComponentsBuilder.fromHttpUrl(pcControlUrl)
                .path("/api/v1/pc/files/content")
                .queryParam("path", path)
                .toUriString();
        
        try {
            return restTemplate.getForObject(url, String.class);
        } catch (Exception e) {
            log.error("Error reading file from pc-control: {}", e.getMessage());
            throw new RuntimeException("Failed to read file: " + e.getMessage());
        }
    }

    public Map<String, Object> getFileInfo(String path) {
        String url = UriComponentsBuilder.fromHttpUrl(pcControlUrl)
                .path("/api/v1/pc/files/info")
                .queryParam("path", path)
                .toUriString();
        
        try {
            return restTemplate.getForObject(url, Map.class);
        } catch (Exception e) {
            log.error("Error getting file info from pc-control: {}", e.getMessage());
            throw new RuntimeException("Failed to get file info: " + e.getMessage());
        }
    }
}
