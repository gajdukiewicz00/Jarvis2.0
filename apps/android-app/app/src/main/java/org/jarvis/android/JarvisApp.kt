package org.jarvis.android

import android.app.Application
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import org.jarvis.android.sync.SyncWorker
import java.util.concurrent.TimeUnit

class JarvisApp : Application() {
    override fun onCreate() {
        super.onCreate()
        scheduleSyncWorker()
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
}
