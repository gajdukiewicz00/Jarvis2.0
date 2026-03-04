package org.jarvis.lifetracker.tooling;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.jarvis.common.exception.IdempotencyConflictException;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class ToolRequestService {

    private final ToolRequestRepository toolRequestRepository;
    private final ObjectMapper objectMapper;

    public String hashRequest(Object request) {
        try {
            String json = objectMapper.writeValueAsString(request);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(json.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder();
            for (byte value : hashed) {
                builder.append(String.format("%02x", value));
            }
            return builder.toString();
        } catch (JsonProcessingException | NoSuchAlgorithmException e) {
            return Integer.toHexString(request.hashCode());
        }
    }

    public <T> Optional<T> loadCachedResponse(
            String idempotencyKey,
            String toolName,
            String userId,
            String requestHash,
            Class<T> responseType) {
        Optional<ToolRequest> existing = toolRequestRepository.findByIdempotencyKey(idempotencyKey);
        if (existing.isEmpty()) {
            return Optional.empty();
        }
        ToolRequest entry = existing.get();
        if (!entry.getToolName().equals(toolName) || !entry.getUserId().equals(userId)) {
            throw new IdempotencyConflictException("Idempotency key reused by a different tool or user");
        }
        if (!entry.getRequestHash().equals(requestHash)) {
            throw new IdempotencyConflictException("Idempotency key reused with different request payload");
        }
        if (entry.getResponseBody() == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readValue(entry.getResponseBody(), responseType));
        } catch (JsonProcessingException e) {
            return Optional.empty();
        }
    }

    public void storeResponse(
            String idempotencyKey,
            String toolName,
            String userId,
            String requestHash,
            Object responseBody) {
        ToolRequest entry = toolRequestRepository.findByIdempotencyKey(idempotencyKey)
                .map(existing -> {
                    if (!existing.getToolName().equals(toolName) || !existing.getUserId().equals(userId)) {
                        throw new IdempotencyConflictException("Idempotency key reused by a different tool or user");
                    }
                    if (!existing.getRequestHash().equals(requestHash)) {
                        throw new IdempotencyConflictException("Idempotency key reused with different request payload");
                    }
                    return existing;
                })
                .orElseGet(ToolRequest::new);

        entry.setIdempotencyKey(idempotencyKey);
        entry.setToolName(toolName);
        entry.setUserId(userId);
        entry.setRequestHash(requestHash);
        try {
            entry.setResponseBody(objectMapper.writeValueAsString(responseBody));
        } catch (JsonProcessingException e) {
            entry.setResponseBody(null);
        }
        toolRequestRepository.save(entry);
    }
}
