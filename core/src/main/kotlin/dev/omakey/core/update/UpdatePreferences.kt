package dev.omakey.core.update

import android.content.Context
import android.content.SharedPreferences
import dev.omakey.core.prefs.PreferenceStore
import kotlinx.coroutines.flow.StateFlow

data class UpdateSettings(
    /** Whether the periodic 12-hour background check (see `app/.../update/UpdateCheckWorker.kt`)
     * is scheduled at all — the manual "Check for updates" button in Settings works regardless of
     * this. Default true: the background check was added at direct user request specifically to
     * replace manual-only checking, so it should be on out of the box rather than a feature nobody
     * discovers. Still a real, visible toggle (not a silent always-on) so anyone who'd rather keep
     * the network call fully opt-in can turn it back off. */
    val autoCheckEnabled: Boolean = true,
)

/** Persists [UpdateSettings] plus which version a background check has already notified about —
 * same SharedPreferences + cross-instance-sync pattern as the other `*Preferences` classes (see
 * HapticSoundPreferences for why the change listener matters: Settings and the periodic worker
 * each construct their own instance). */
class UpdatePreferences(context: Context) {
    private val store = PreferenceStore(context, PREFS_NAME, ::load)
    val settings: StateFlow<UpdateSettings> = store.settings

    fun close() = store.close()

    fun setAutoCheckEnabled(enabled: Boolean) {
        store.edit { putBoolean(KEY_AUTO_CHECK_ENABLED, enabled) }
    }

    /** The latest version the background worker has already notified about — checked before
     * posting a new notification so an unchanged "update available" result on the next 12-hour
     * tick doesn't re-notify for the same release over and over. Reset implicitly the moment a
     * *newer* version is seen (see `UpdateCheckWorker`'s own call site), not cleared on app
     * update — if the user is still on an old build, there's nothing to "reset." */
    fun lastNotifiedVersion(): String? = store.prefs.getString(KEY_LAST_NOTIFIED_VERSION, null)

    fun setLastNotifiedVersion(version: String) {
        store.edit { putString(KEY_LAST_NOTIFIED_VERSION, version) }
    }

    private fun load(prefs: SharedPreferences) = UpdateSettings(
        autoCheckEnabled = prefs.getBoolean(KEY_AUTO_CHECK_ENABLED, true),
    )

    private companion object {
        const val PREFS_NAME = "omakey_update_prefs"
        const val KEY_AUTO_CHECK_ENABLED = "auto_check_enabled"
        const val KEY_LAST_NOTIFIED_VERSION = "last_notified_version"
    }
}
