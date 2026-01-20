package org.jarvis.memory.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jarvis.memory.dto.SearchRequest;
import org.jarvis.memory.dto.SearchResponse;
import org.jarvis.memory.service.MemoryService;
import org.jarvis.memory.tooling.dto.SearchMemoryToolRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequestMapping("/api/v1/tools/memory")
@RequiredArgsConstructor
@Validated
public class ToolMemoryController {

    private final MemoryService memoryService;

    @PostMapping("/search")
    public ResponseEntity<SearchResponse> search(
            @RequestAttribute("toolUserId") String userId,
            @Valid @RequestBody SearchMemoryToolRequest request,
            @RequestHeader(value = "X-Correlation-ID", required = false) String correlationId) {

        SearchRequest searchRequest = SearchRequest.builder()
                .userId(userId)
                .query(request.getQuery())
                .topK(request.getTopK() != null ? request.getTopK() : 5)
                .maxTokens(request.getMaxTokens() != null ? request.getMaxTokens() : 600)
                .build();

        SearchResponse response = memoryService.search(searchRequest,
                correlationId != null ? correlationId : "tool-memory");
        return ResponseEntity.ok(response);
    }
}
