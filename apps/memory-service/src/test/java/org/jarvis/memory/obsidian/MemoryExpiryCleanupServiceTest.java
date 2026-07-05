package org.jarvis.memory.obsidian;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MemoryExpiryCleanupServiceTest {

    private static final Instant FIXED_NOW = Instant.parse("2026-07-05T12:00:00Z");

    private MemoryNoteRepository repository;
    private MemoryForgetService forgetService;
    private MemoryExpiryProperties properties;
    private MemoryExpiryCleanupService service;

    @BeforeEach
    void setUp() {
        repository = mock(MemoryNoteRepository.class);
        forgetService = mock(MemoryForgetService.class);
        properties = new MemoryExpiryProperties();
        Clock fixedClock = Clock.fixed(FIXED_NOW, ZoneOffset.UTC);
        service = new MemoryExpiryCleanupService(repository, forgetService, properties, fixedClock);
    }

    private MemoryNoteEntity expiredNote(String memoryId) {
        return MemoryNoteEntity.builder()
                .memoryId(memoryId)
                .category(MemoryCategory.PROJECTS.name())
                .status("ACTIVE")
                .expiresAt(FIXED_NOW.minusSeconds(60))
                .build();
    }

    @Test
    void cleanupExpiredNotesForgetsEachExpiredActiveNote() {
        when(repository.findByStatusAndExpiresAtBefore("ACTIVE", FIXED_NOW))
                .thenReturn(List.of(expiredNote("mem-1"), expiredNote("mem-2")));

        int removed = service.cleanupExpiredNotes();

        assertThat(removed).isEqualTo(2);
        verify(forgetService).forget(eq("mem-1"), eq("system"), eq("ttl-expired"));
        verify(forgetService).forget(eq("mem-2"), eq("system"), eq("ttl-expired"));
    }

    @Test
    void cleanupExpiredNotesReturnsZeroWhenNothingExpired() {
        when(repository.findByStatusAndExpiresAtBefore("ACTIVE", FIXED_NOW)).thenReturn(List.of());

        int removed = service.cleanupExpiredNotes();

        assertThat(removed).isZero();
        verify(forgetService, never()).forget(any(), any(), any());
    }

    @Test
    void scheduledCleanupSkipsWhenDisabled() {
        properties.setEnabled(false);

        service.scheduledCleanup();

        verify(repository, never()).findByStatusAndExpiresAtBefore(any(), any());
    }

    @Test
    void scheduledCleanupRunsWhenEnabled() {
        properties.setEnabled(true);
        when(repository.findByStatusAndExpiresAtBefore("ACTIVE", FIXED_NOW))
                .thenReturn(List.of(expiredNote("mem-3")));

        service.scheduledCleanup();

        verify(forgetService, times(1)).forget(eq("mem-3"), eq("system"), eq("ttl-expired"));
    }
}
