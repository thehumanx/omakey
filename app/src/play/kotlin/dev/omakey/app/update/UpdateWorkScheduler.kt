package dev.omakey.app.update

import android.content.Context

/**
 * Play flavor: there is no update check, so there is nothing to schedule. Same API as the sideload
 * flavor's real scheduler (src/sideload/.../UpdateWorkScheduler.kt) so shared call sites compile
 * unchanged. The `work-runtime` dependency is sideload-only, which keeps WorkManager's boot,
 * wake-lock and foreground-service permissions out of the Play manifest.
 */
@Suppress("UNUSED_PARAMETER")
object UpdateWorkScheduler {
    fun schedule(context: Context) = Unit
    fun cancel(context: Context) = Unit
}
