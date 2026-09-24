package dev.omakey.core.pack

import kotlinx.serialization.Serializable

/**
 * `manifest.json` at the root of a language pack — a zip of data only, never code (AGENTS.md §66
 * Phase 7). Describes the language and where its parts are inside the pack.
 */
@Serializable
data class PackManifest(
    /** The language's `KeyboardLocale.id`, e.g. `es_ES`. Also the install directory's name. */
    val id: String,
    val displayName: String,
    val nativeName: String,
    /** Semver of the pack's *data*, independent of the app's version. */
    val packVersion: String,
    /** Layout of the pack itself; an app refuses packs whose format it doesn't know. */
    val packFormat: Int,
    /** Oldest app `versionCode` that can use this pack. */
    val minAppVersionCode: Int = 0,
    /** Id of the layout in [layouts] that is the language's letter layout. */
    val letterLayout: String,
    /** Letter layouts the user can choose between (AZERTY or QWERTY for French), first the
     * default; empty when there's only [letterLayout]. */
    val letterLayoutChoices: List<String> = emptyList(),
    /** Paths inside the pack of every layout JSON, the letter layout included. */
    val layouts: List<String>,
    val model: String = "lm.bin",
    val profile: String = "profile.json",
    /** Licence of the pack's data as a whole, and what it was built from — shown in Settings. */
    val license: String = "",
    val sources: List<PackSource> = emptyList(),
) {
    companion object {
        /** The pack format this app reads. */
        const val FORMAT = 1
    }
}

@Serializable
data class PackSource(val name: String, val url: String = "", val license: String = "")
