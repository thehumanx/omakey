package dev.omakey.app.update

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dev.omakey.app.BuildConfig
import dev.omakey.app.R
import dev.omakey.core.update.GithubReleaseUpdateChecker
import dev.omakey.core.update.UpdateCheckOutcome
import dev.omakey.core.update.UpdatePreferences

/**
 * The periodic (every 12h, see [UpdateWorkScheduler]) half of the update checker — the manual
 * "Check for updates" button in Settings (`UpdateCheckRow`) is unaffected by this, it's a
 * completely separate call site hitting the same [GithubReleaseUpdateChecker]. Runs only while
 * [UpdatePreferences.autoCheckEnabled] is on; posts a system notification rather than anything
 * intrusive mid-typing, and only once per newly-seen version (see [UpdatePreferences
 * .lastNotifiedVersion]) so an unchanged "still on 2.3.0" result doesn't re-notify every 12 hours.
 */
class UpdateCheckWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val prefs = UpdatePreferences(applicationContext)
        if (!prefs.settings.value.autoCheckEnabled) return Result.success()

        return when (val outcome = GithubReleaseUpdateChecker().checkForUpdate(BuildConfig.VERSION_NAME)) {
            is UpdateCheckOutcome.Success -> {
                val result = outcome.result
                if (result.updateAvailable && result.latestVersion != prefs.lastNotifiedVersion()) {
                    if (notify(result.latestVersion, result.releaseUrl)) {
                        prefs.setLastNotifiedVersion(result.latestVersion)
                    }
                    // Not notifying (permission missing/denied) isn't a worker failure — there's
                    // nothing retrying sooner would fix, the next regular 12h tick will just try
                    // again with whatever permission state exists by then.
                }
                Result.success()
            }
            // Transient (offline, GitHub unreachable, etc.) — WorkManager's own periodic schedule
            // already covers "try again later," no need for this worker's own retry/backoff on
            // top of that.
            UpdateCheckOutcome.Error -> Result.success()
        }
    }

    /** Returns whether a notification was actually posted — false when the app lacks
     * [Manifest.permission.POST_NOTIFICATIONS] (Android 13+) or the user has notifications
     * disabled at the OS level, either of which just means "silently don't notify," never a
     * crash. */
    private fun notify(latestVersion: String, releaseUrl: String): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ActivityCompat.checkSelfPermission(applicationContext, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Updates", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "Lets you know when a new omakey release is available"
                },
            )
        }
        val viewIntent = Intent(Intent.ACTION_VIEW, Uri.parse(releaseUrl))
        val pendingIntent = PendingIntent.getActivity(
            applicationContext, 0, viewIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("omakey update available")
            .setContentText("Version $latestVersion is out — tap to view.")
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()
        manager.notify(NOTIFICATION_ID, notification)
        return true
    }

    private companion object {
        const val CHANNEL_ID = "omakey_updates"
        const val NOTIFICATION_ID = 1001
    }
}
