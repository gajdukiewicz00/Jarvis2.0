package org.jarvis.android.notifications

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import org.jarvis.android.data.local.JarvisDatabase

/**
 * Increment E (bank push -> finance draft) — periodic purge of bank-notification-derived
 * `FINANCE_ENTRY` drafts whose raw (sanitized) text has outlived the user's configured
 * retention window (see [BankAppSettings.retentionDays]).
 *
 * <p>Runs independently of [org.jarvis.android.sync.SyncWorker]: a draft is purged
 * locally once it's too old, whether or not it has already synced — the point is
 * bounding how long sanitized notification text sits on the device, not whether the
 * server has seen it. Manual finance entries and every other `PendingItem` kind are
 * left alone; see [BankDraftRetention.isBankNotificationDraft].</p>
 */
class BankDraftPurgeWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        val settings = BankAppSettings(applicationContext)
        val dao = JarvisDatabase.get(applicationContext).pendingItems()
        val retentionDays = settings.retentionDays()
        val now = System.currentTimeMillis()

        dao.byKind(FINANCE_ENTRY_KIND)
            .asSequence()
            .filter { BankDraftRetention.isBankNotificationDraft(it.payloadJson) }
            .filter { BankDraftRetention.isExpired(it.createdAtEpochMs, now, retentionDays) }
            .forEach { dao.deleteById(it.id) }

        return Result.success()
    }

    private companion object {
        const val FINANCE_ENTRY_KIND = "FINANCE_ENTRY"
    }
}
