package org.jarvis.memory.obsidian;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.jarvis.memory.exception.MemoryExportEncryptionUnavailableException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * Roadmap P1 #9 — bulk export/import of memory notes as JSON, with an
 * optional (flagged) AES-256/GCM encryption layer for the takeout payload.
 *
 * <p>Plain export/import already existed as {@code GET /notes/export} +
 * {@link MemoryNoteService#write}; this adds an encrypted variant and a
 * bulk-import path that reuses the same ingest pipeline (dedup, scope, TTL,
 * "why remembered" all apply identically to imported notes).</p>
 */
@Slf4j
@Service
public class MemoryExportService {

    private static final String AES_ALGORITHM = "AES/GCM/NoPadding";
    private static final int GCM_TAG_BITS = 128;
    private static final int GCM_IV_BYTES = 12;

    private final MemoryNoteRepository repository;
    private final MemoryNoteService noteService;
    private final MemoryExportProperties properties;
    private final ObjectMapper objectMapper;

    public MemoryExportService(MemoryNoteRepository repository,
                               MemoryNoteService noteService,
                               MemoryExportProperties properties,
                               ObjectMapper objectMapper) {
        this.repository = repository;
        this.noteService = noteService;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    /** AES-256/GCM encrypted takeout payload. Self-contained: carries its own IV. */
    public record ExportEnvelope(String algorithm, String ivBase64, String ciphertextBase64) {}

    /** Outcome of a bulk import: how many notes were created vs. merged (dedup) vs. failed. */
    public record ImportSummary(int received, int created, int merged, int failed, List<String> errors) {}

    /**
     * Roadmap P1 #9 — result of {@link #exportPreferEncrypted}: {@code encrypted}
     * is the explicit flag callers should branch on (never guess from which of
     * {@code envelope}/{@code notes} is null).
     */
    public record ExportPayload(boolean encrypted, ExportEnvelope envelope, List<MemoryNoteEntity> notes) {}

    /** Outcome of a conflict-aware bulk import (see {@link ImportConflictMode}). */
    public record ConflictImportSummary(int received, int created, int overwritten, int skipped, int failed,
                                        List<String> errors) {}

    /** All ACTIVE notes, optionally filtered by scope — the plaintext takeout payload. */
    public List<MemoryNoteEntity> exportNotes(MemoryScope scope) {
        String scopeStr = scope == null ? null : scope.name();
        return repository.searchByCategoryAndScope(null, scopeStr, "ACTIVE", PageRequest.of(0, 500));
    }

    /**
     * Same as {@link #exportNotes} but AES-256/GCM-encrypted. Requires
     * {@code jarvis.memory.export.encryption-key-base64} to be configured.
     */
    public ExportEnvelope exportEncrypted(MemoryScope scope) {
        requireEncryptionConfigured();
        String json = writeJson(exportNotes(scope));
        return encrypt(json);
    }

    /**
     * Roadmap P1 #9 — {@link #exportEncrypted} when a key is configured;
     * otherwise falls back to the existing plaintext takeout
     * ({@link #exportNotes}) rather than failing the request outright. The
     * caller always gets an explicit {@code encrypted} flag on the returned
     * {@link ExportPayload} instead of having to infer the mode from which
     * field is null.
     */
    public ExportPayload exportPreferEncrypted(MemoryScope scope) {
        if (properties.isEncryptionConfigured()) {
            return new ExportPayload(true, exportEncrypted(scope), null);
        }
        return new ExportPayload(false, null, exportNotes(scope));
    }

    /**
     * Bulk-imports notes, one {@link MemoryNoteService#writeWithOutcome} call
     * per entry so dedup/scope/TTL apply exactly as they would to a single
     * {@code POST /notes}. A failure on one entry does not abort the batch.
     */
    public ImportSummary importNotes(List<MemoryNoteRequest> requests) {
        if (requests == null || requests.isEmpty()) {
            return new ImportSummary(0, 0, 0, 0, List.of());
        }
        int created = 0;
        int merged = 0;
        List<String> errors = new ArrayList<>();
        for (MemoryNoteRequest request : requests) {
            try {
                MemoryNoteService.WriteOutcome outcome = noteService.writeWithOutcome(request);
                if (outcome.merged()) {
                    merged++;
                } else {
                    created++;
                }
            } catch (RuntimeException e) {
                String title = request == null ? "<null>" : String.valueOf(request.getTitle());
                errors.add("\"" + title + "\": " + e.getMessage());
                log.warn("bulk import: failed to write note '{}': {}", title, e.getMessage());
            }
        }
        return new ImportSummary(requests.size(), created, merged, errors.size(), List.copyOf(errors));
    }

    /** Decrypts an {@link ExportEnvelope} and imports the notes it carries. */
    public ImportSummary importEncrypted(ExportEnvelope envelope) {
        requireEncryptionConfigured();
        String json = decrypt(envelope);
        List<MemoryNoteRequest> requests = readJson(json);
        return importNotes(requests);
    }

    /**
     * Roadmap P1 #9 — bulk import with explicit conflict resolution. A
     * "conflict" is an existing ACTIVE note matched either by {@code memoryId}
     * (exact match, checked first) or, failing that, by content-hash (same
     * normalized title+body — the same signal {@link MemoryNoteService}'s
     * ingest-time dedup uses). Unlike {@link #importNotes} (which always
     * defers to the ambient {@link MemoryDedupProperties} merge-by-default
     * behaviour), the caller's {@code mode} is authoritative here: SKIP drops
     * the incoming entry, OVERWRITE replaces the conflicting note in place,
     * and KEEP_BOTH creates a genuinely new note (bypassing dedup) alongside
     * the existing one.
     */
    public ConflictImportSummary importNotesWithConflictResolution(List<MemoryNoteRequest> requests,
                                                                    ImportConflictMode mode) {
        if (requests == null || requests.isEmpty()) {
            return new ConflictImportSummary(0, 0, 0, 0, 0, List.of());
        }
        ImportConflictMode effectiveMode = mode == null ? ImportConflictMode.SKIP : mode;
        int created = 0;
        int overwritten = 0;
        int skipped = 0;
        List<String> errors = new ArrayList<>();
        for (MemoryNoteRequest request : requests) {
            try {
                MemoryNoteEntity conflict = findConflict(request);
                if (conflict == null) {
                    noteService.writeWithOutcome(request);
                    created++;
                    continue;
                }
                switch (effectiveMode) {
                    case OVERWRITE -> {
                        noteService.update(conflict.getMemoryId(), request);
                        overwritten++;
                    }
                    case KEEP_BOTH -> {
                        noteService.writeWithOutcome(stripMemoryId(request), true);
                        created++;
                    }
                    default -> skipped++;
                }
            } catch (RuntimeException e) {
                String title = request == null ? "<null>" : String.valueOf(request.getTitle());
                errors.add("\"" + title + "\": " + e.getMessage());
                log.warn("bulk import (conflict resolution): failed to write note '{}': {}", title, e.getMessage());
            }
        }
        return new ConflictImportSummary(requests.size(), created, overwritten, skipped, errors.size(),
                List.copyOf(errors));
    }

    /**
     * Decrypts an {@link ExportEnvelope} and applies
     * {@link #importNotesWithConflictResolution} to the notes it carries.
     */
    public ConflictImportSummary importEncryptedWithConflictResolution(ExportEnvelope envelope,
                                                                        ImportConflictMode mode) {
        requireEncryptionConfigured();
        String json = decrypt(envelope);
        List<MemoryNoteRequest> requests = readJson(json);
        return importNotesWithConflictResolution(requests, mode);
    }

    /** Existing note conflicting with {@code request}: same memoryId first, else same content-hash. */
    private MemoryNoteEntity findConflict(MemoryNoteRequest request) {
        if (request == null) {
            return null;
        }
        if (request.getMemoryId() != null && !request.getMemoryId().isBlank()) {
            MemoryNoteEntity byId = noteService.get(request.getMemoryId());
            if (byId != null) {
                return byId;
            }
        }
        String hash = MemoryNoteService.computeContentHash(request.getTitle(), request.getBody());
        return repository.findFirstByContentHashAndStatusOrderByCreatedAtDesc(hash, "ACTIVE").orElse(null);
    }

    /** Forces a fresh memoryId so "keep both" cannot collide with the conflicting note. */
    private static MemoryNoteRequest stripMemoryId(MemoryNoteRequest request) {
        return request.toBuilder().memoryId(null).build();
    }

    private void requireEncryptionConfigured() {
        if (!properties.isEncryptionConfigured()) {
            throw new MemoryExportEncryptionUnavailableException(
                    "jarvis.memory.export.encryption-key-base64 is not configured; "
                            + "encrypted export/import is disabled");
        }
    }

    private ExportEnvelope encrypt(String plaintext) {
        try {
            byte[] key = Base64.getDecoder().decode(properties.getEncryptionKeyBase64());
            byte[] iv = new byte[GCM_IV_BYTES];
            new SecureRandom().nextBytes(iv);
            Cipher cipher = Cipher.getInstance(AES_ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return new ExportEnvelope("AES/GCM/NoPadding",
                    Base64.getEncoder().encodeToString(iv),
                    Base64.getEncoder().encodeToString(ciphertext));
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new MemoryExportEncryptionUnavailableException("failed to encrypt export payload", e);
        }
    }

    private String decrypt(ExportEnvelope envelope) {
        if (envelope == null || envelope.ivBase64() == null || envelope.ciphertextBase64() == null) {
            throw new MemoryExportEncryptionUnavailableException("encrypted import payload is incomplete");
        }
        try {
            byte[] key = Base64.getDecoder().decode(properties.getEncryptionKeyBase64());
            byte[] iv = Base64.getDecoder().decode(envelope.ivBase64());
            byte[] ciphertext = Base64.getDecoder().decode(envelope.ciphertextBase64());
            Cipher cipher = Cipher.getInstance(AES_ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] plaintext = cipher.doFinal(ciphertext);
            return new String(plaintext, java.nio.charset.StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new MemoryExportEncryptionUnavailableException(
                    "failed to decrypt import payload (wrong key or corrupted data)", e);
        }
    }

    private String writeJson(List<MemoryNoteEntity> notes) {
        try {
            return objectMapper.writeValueAsString(notes);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to serialize export payload", e);
        }
    }

    /**
     * Parses the decrypted payload as note-requests. Ignores unknown JSON
     * properties: {@code /export/encrypted} serializes full
     * {@link MemoryNoteEntity} objects (read-model, includes computed/DB-only
     * fields like {@code status}/{@code contentHash}/{@code whyRemembered}),
     * while import only cares about the write-model {@link MemoryNoteRequest}
     * fields — so a straight export-then-import round trip must not choke on
     * the extra fields.
     */
    private List<MemoryNoteRequest> readJson(String json) {
        try {
            ObjectMapper lenient = objectMapper.copy()
                    .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
            return lenient.readValue(json, new TypeReference<List<MemoryNoteRequest>>() {});
        } catch (JsonProcessingException e) {
            throw new MemoryExportEncryptionUnavailableException(
                    "decrypted payload was not a valid note-request array", e);
        }
    }
}
