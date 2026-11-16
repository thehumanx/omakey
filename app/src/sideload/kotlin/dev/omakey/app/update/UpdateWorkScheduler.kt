package dev.omakey.app.update

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * Schedules/cancels [UpdateCheckWorker]'s periodic run. Called from both
 * `OmakeyInputMethodService.onCreate()` (runs whenever the keyboard process starts, which is
 * effectively "whenever the phone is used," and survives reboot without needing a
 * `RECEIVE_BOOT_COMPLETED` broadcast receiver of its own) and `SettingsActivity`'s toggle — both
 * calls are idempotent (`ExistingPeriodicWorkPolicy.KEEP`/`enqueueUniqueWork` semantics), so
 * calling this on every service/activity start is cheap and safe rather than needing its own
 * "have I already scheduled this" bookkeeping.
 */
object UpdateWorkScheduler {
    private const val UNIQUE_WORK_NAME = "omakey_update_check"

    fun schedule(context: Context) {
        val request = PeriodicWorkRequestBuilder<UpdateCheckWorker>(12, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            UNIQUE_WORK_NAME,
            // KEEP, not REPLACE — re-calling this on every service/activity start shouldn't reset
            // an already-running 12h cycle back to zero each time.
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_WORK_NAME)
    }
}
