package org.jarvis.android

import android.app.Application
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import org.jarvis.android.notifications.BankDraftPurgeWorker
import org.jarvis.android.sync.SyncWorker
import java.util.concurrent.TimeUnit

class JarvisApp : Application() {
    override fun onCreate() {
        super.onCreate()
        scheduleSyncWorker()
        scheduleBankDraftPurgeWorker()
    }

    /** Drains the offline queue every 15 min when the network is up. */
    private fun scheduleSyncWorker() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val request = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "jarvis-sync",
            ExistingPeriodicWorkPolicy.KEEP,
            request
        )
    }

    /** Increment E (bank push -> finance draft): sweeps expired raw-text drafts daily. */
    private fun scheduleBankDraftPurgeWorker() {
        val request = PeriodicWorkRequestBuilder<BankDraftPurgeWorker>(1, TimeUnit.DAYS).build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "jarvis-bank-draft-purge",
            ExistingPeriodicWorkPolicy.KEEP,
            request
        )
    }
}
