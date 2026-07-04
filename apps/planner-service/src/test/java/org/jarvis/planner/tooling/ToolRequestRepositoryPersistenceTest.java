package org.jarvis.planner.tooling;

import org.jarvis.planner.support.PlannerPostgresContainerSupport;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Tag("integration")
class ToolRequestRepositoryPersistenceTest extends PlannerPostgresContainerSupport {

    @Autowired
    private ToolRequestRepository toolRequestRepository;

    @Test
    void findByIdempotencyKeyReturnsExactPersistedRequest() {
        toolRequestRepository.saveAndFlush(newRequest("key-1", "create_todo", "user-1", "hash-1", "{\"ok\":true}"));
        toolRequestRepository.saveAndFlush(newRequest("key-2", "update_todo", "user-2", "hash-2", "{\"ok\":false}"));

        ToolRequest request = toolRequestRepository.findByIdempotencyKey("key-1").orElseThrow();

        assertThat(request.getToolName()).isEqualTo("create_todo");
        assertThat(request.getUserId()).isEqualTo("user-1");
        assertThat(request.getRequestHash()).isEqualTo("hash-1");
        assertThat(request.getResponseBody()).isEqualTo("{\"ok\":true}");
    }

    @Test
    void deleteByCreatedAtBeforeDeletesOlderRowsAndKeepsNewerOnes() {
        ToolRequest older = toolRequestRepository.saveAndFlush(
                newRequest("old-key", "create_todo", "user-1", "hash-old", "{\"status\":\"old\"}"));
        pauseBriefly();
        Instant cutoff = Instant.now();
        pauseBriefly();
        ToolRequest newer = toolRequestRepository.saveAndFlush(
                newRequest("new-key", "create_todo", "user-1", "hash-new", "{\"status\":\"new\"}"));

        long deleted = toolRequestRepository.deleteByCreatedAtBefore(cutoff);

        assertThat(deleted).isEqualTo(1);
        assertThat(toolRequestRepository.findById(older.getId())).isEmpty();
        assertThat(toolRequestRepository.findById(newer.getId())).isPresent();
    }

    @Test
    void deleteByCreatedAtBeforeIsStrictAtTimestampBoundary() {
        ToolRequest request = toolRequestRepository.saveAndFlush(
                newRequest("boundary-key", "create_todo", "user-1", "hash-boundary", "{\"status\":\"boundary\"}"));

        long deleted = toolRequestRepository.deleteByCreatedAtBefore(request.getCreatedAt());

        assertThat(deleted).isZero();
        assertThat(toolRequestRepository.findById(request.getId())).isPresent();
    }

    @Test
    void savingDuplicateIdempotencyKeyViolatesUniqueConstraint() {
        toolRequestRepository.saveAndFlush(newRequest("dup-key", "create_todo", "user-1", "hash-1", "{\"ok\":true}"));

        assertThatThrownBy(() -> toolRequestRepository.saveAndFlush(
                newRequest("dup-key", "complete_todo", "user-2", "hash-2", "{\"ok\":false}")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private void pauseBriefly() {
        try {
            Thread.sleep(25);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while separating creation timestamps", ex);
        }
    }

    private ToolRequest newRequest(String idempotencyKey, String toolName, String userId, String requestHash,
            String responseBody) {
        ToolRequest request = new ToolRequest();
        request.setIdempotencyKey(idempotencyKey);
        request.setToolName(toolName);
        request.setUserId(userId);
        request.setRequestHash(requestHash);
        request.setResponseBody(responseBody);
        return request;
    }
}
