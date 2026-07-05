package org.jarvis.android.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Phase 12 — offline-first queue of items the phone owes the home server.
 *
 * <p>Every user action (manual finance entry, voice command, ...) lands
 * here first; SyncWorker drains the queue when connectivity is up.
 * The phone is fully usable offline — UI reads from this same DB plus
 * cached server snapshots, never blocking on network.</p>
 */
@Entity(tableName = "pending_items")
data class PendingItem(
    @PrimaryKey val id: String,           // UUID generated client-side; doubles as clientNonce for sync
    val kind: String,                     // matches SyncPayloadKind on the server
    val payloadJson: String,              // arbitrary JSON, opaque from this layer's POV
    val createdAtEpochMs: Long,
    val attemptCount: Int = 0,
    val lastAttemptEpochMs: Long? = null,
    val lastError: String? = null,
    val syncedAtEpochMs: Long? = null
)
