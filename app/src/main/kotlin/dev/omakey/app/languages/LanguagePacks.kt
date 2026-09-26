package dev.omakey.app.languages

import android.content.Context
import android.util.Log
import dev.omakey.app.BuildConfig
import dev.omakey.core.locale.KeyboardLocale
import dev.omakey.core.locale.LocalePreferences
import dev.omakey.core.locale.LocaleRegistry
import dev.omakey.core.pack.PackDownloader
import dev.omakey.core.pack.PackIndexEntry
import dev.omakey.core.pack.PackInstaller
import dev.omakey.core.pack.PackManifest
import dev.omakey.core.pack.PackLoader
import java.io.File

/**
 * The app's one [LocaleRegistry], plus installing and removing language packs (AGENTS.md §66
 * Phase 7).
 *
 * Process-wide rather than per component: Settings and the IME service run in the same process, so
 * a pack installed from Settings is registered in the registry the open keyboard is already
 * observing, and appears there without a restart.
 *
 * Installed packs are loaded once, on first use. A pack that no longer loads (a format this build
 * doesn't read, corrupted storage) is skipped and logged rather than allowed to break the keyboard.
 */
class LanguagePacks private constructor(context: Context) {

    private val root = File(context.filesDir, "languages")
    private val installer = PackInstaller(root, BuildConfig.VERSION_CODE)
    private val downloader = PackDownloader()
    private val cache = File(context.cacheDir, "language-downloads")

    val registry = LocaleRegistry()

    init {
        installer.cleanUp()
        val failures = mutableMapOf<String, String>()
        for (locale in installer.installed(failures)) {
            try {
                registry.register(locale)
            } catch (e: IllegalArgumentException) {
                failures[locale.id] = e.message ?: "invalid"
            }
        }
        failures.forEach { (id, reason) -> Log.w(TAG, "Skipping language pack $id: $reason") }
    }

    /** The signed index of downloadable packs. Network; call off the main thread. */
    fun fetchIndex() = downloader.fetchIndex()

    fun installedVersions(): Map<String, String> = installer.installedVersions()

    /** Manifest of an installed pack — its licence and sources, for Settings. */
    fun manifestOf(id: String): PackManifest? =
        runCatching { PackLoader.readManifest(File(root, id)) }.getOrNull()

    /** Downloads, verifies and installs [entry], then registers and enables its language.
     * Network and disk; call off the main thread. */
    fun downloadAndInstall(entry: PackIndexEntry, preferences: LocalePreferences, onProgress: (Float) -> Unit): KeyboardLocale {
        val zip = File(cache, entry.file)
        try {
            downloader.download(entry, zip, onProgress)
            return installAndEnable(zip, entry.sha256, preferences)
        } finally {
            zip.delete()
        }
    }

    /** Installs a pack the user picked from storage. There is no signed checksum to hold it to, so
     * it gets every structural check but not the integrity one — which is why Settings asks first. */
    fun installFromFile(zip: File, preferences: LocalePreferences): KeyboardLocale = installAndEnable(zip, null, preferences)

    fun uninstall(id: String, preferences: LocalePreferences) {
        preferences.setEnabled(preferences.settings.value.enabledIds - id)
        registry.unregister(id)
        installer.uninstall(id)
    }

    private fun installAndEnable(zip: File, sha256: String?, preferences: LocalePreferences): KeyboardLocale {
        val locale = installer.install(zip, sha256)
        registry.register(locale)
        // Downloading a language is a clear enough statement of intent to switch it on.
        val enabled = preferences.settings.value.enabledIds
        if (locale.id !in enabled) preferences.setEnabled(enabled + locale.id)
        return locale
    }

    companion object {
        private const val TAG = "LanguagePacks"

        @Volatile private var instance: LanguagePacks? = null

        fun get(context: Context): LanguagePacks =
            instance ?: synchronized(this) { instance ?: LanguagePacks(context.applicationContext).also { instance = it } }
    }
}
