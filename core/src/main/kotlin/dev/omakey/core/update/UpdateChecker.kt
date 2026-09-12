package dev.omakey.core.update

import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Version checking against the public GitHub Releases API for this repo, and nothing else — no
 * telemetry, no analytics, no third-party update service, and never an auto-download or install.
 *
 * Two callers, both opt-out-able: the "Check for updates" button in Settings, and
 * `UpdateCheckWorker`'s periodic 12-hourly poll (gated on `UpdatePreferences.autoCheckEnabled`).
 * The `INTERNET` permission this needs is the single deliberate exception to omakey being
 * offline-by-default; see AGENTS.md §16/17.
 *
 * (This doc used to claim "manual only, no background polling" and that `INTERNET` was declared
 * nowhere. Both stopped being true when the worker landed, and stale documentation on the one
 * network-touching component in a keyboard app is the worst place to have it.)
 */
data class UpdateCheckResult(
    val updateAvailable: Boolean,
    val latestVersion: String,
    /** Browser-openable GitHub Releases page for the latest release — the "View" action opens
     * this rather than the app trying to download/install the APK itself, which would need
     * `REQUEST_INSTALL_PACKAGES` and a `FileProvider` (real added attack surface for a keyboard
     * app, deliberately not built). */
    val releaseUrl: String,
)

sealed interface UpdateCheckOutcome {
    data class Success(val result: UpdateCheckResult) : UpdateCheckOutcome
    /** Covers both "genuinely offline" and any unexpected API/parsing failure — same user-facing
     * treatment either way ("Couldn't check for updates"), since neither is actionable beyond
     * "try again later." */
    data object Error : UpdateCheckOutcome
}

interface UpdateChecker {
    /** [currentVersion] is the running app's own `versionName` (e.g. "2.2.2", no leading "v") —
     * passed in rather than read internally so this class stays testable without an Android
     * `Context`/`BuildConfig` dependency. */
    suspend fun checkForUpdate(currentVersion: String): UpdateCheckOutcome
}

/** Real implementation — plain `HttpURLConnection` + `org.json` (both already part of the Android
 * platform SDK) rather than pulling in a new HTTP client dependency for the one network call this
 * entire app makes. */
class GithubReleaseUpdateChecker(
    private val repoOwner: String = "thehumanx",
    private val repoName: String = "omakey",
) : UpdateChecker {
    override suspend fun checkForUpdate(currentVersion: String): UpdateCheckOutcome =
        withContext(Dispatchers.IO) {
            try {
                val url = URL("https://api.github.com/repos/$repoOwner/$repoName/releases/latest")
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.setRequestProperty("Accept", "application/vnd.github+json")
                connection.connectTimeout = TIMEOUT_MS
                connection.readTimeout = TIMEOUT_MS
                try {
                    if (connection.responseCode != HttpURLConnection.HTTP_OK) return@withContext UpdateCheckOutcome.Error
                    val body = connection.inputStream.bufferedReader().use { it.readText() }
                    val json = JSONObject(body)
                    val tagName = json.getString("tag_name")
                    val releaseUrl = json.getString("html_url")
                    val latestVersion = tagName.removePrefix("v")
                    UpdateCheckOutcome.Success(
                        UpdateCheckResult(
                            updateAvailable = isNewerVersion(latestVersion, currentVersion),
                            latestVersion = latestVersion,
                            releaseUrl = safeReleaseUrl(releaseUrl),
                        ),
                    )
                } finally {
                    connection.disconnect()
                }
            } catch (_: Exception) {
                UpdateCheckOutcome.Error
            }
        }

    /**
     * [url] is a string pulled out of a network response and handed, unexamined, to
     * `Intent(ACTION_VIEW)` in a notification the user is invited to tap. That is an
     * untrusted-input-to-Intent flow, and the fact that today's only endpoint is GitHub over HTTPS
     * is a property of the deployment, not of the code — a redirected/spoofed/compromised response
     * could name any scheme, and `ACTION_VIEW` on a non-`http(s)` scheme resolves to whatever app
     * claims it. A keyboard is the wrong place to be relaxed about that.
     *
     * Anything that isn't HTTPS on github.com is replaced with this repo's own releases page rather
     * than failing the check: the version information is still perfectly good, and the user should
     * still be told an update exists.
     */
    private fun safeReleaseUrl(url: String): String {
        val fallback = "https://github.com/$repoOwner/$repoName/releases/latest"
        val parsed = runCatching { URL(url) }.getOrNull() ?: return fallback
        val host = parsed.host.lowercase()
        val trusted = host == "github.com" || host.endsWith(".github.com")
        return if (parsed.protocol.equals("https", ignoreCase = true) && trusted) url else fallback
    }

    private fun isNewerVersion(latest: String, current: String): Boolean {
        val latestParts = latest.split(".").map { it.toIntOrNull() ?: 0 }
        val currentParts = current.split(".").map { it.toIntOrNull() ?: 0 }
        val length = maxOf(latestParts.size, currentParts.size)
        for (i in 0 until length) {
            val l = latestParts.getOrElse(i) { 0 }
            val c = currentParts.getOrElse(i) { 0 }
            if (l != c) return l > c
        }
        return false
    }

    private companion object {
        const val TIMEOUT_MS = 10_000
    }
}
