package dev.omakey.core.locale

import dev.omakey.core.layout.KeyboardLayout
import dev.omakey.core.layout.Layouts

/**
 * One language the keyboard can type: everything that has to change together when the user
 * switches.
 *
 *  - **Layout** — [letterLayout] and [extraLayouts]. Autocorrect's key-adjacency table is derived
 *    from the letter layout (`KeyboardGeometry.from`), so a new layout gets correct neighbours.
 *  - **Language model** — [languageModel], built by `scripts/build_lm.py`. Bundled for English,
 *    downloaded in a language pack otherwise (AGENTS.md §66 Phase 7).
 *  - **Text rules and data tables** — [profile]: what counts as part of a word, sentence
 *    punctuation, contractions, equivalent letters, emoji words.
 *  - **Personal vocabulary** — not held here, but keyed by [id] in the `words` table (`locale`
 *    column, database v5), so each language learns separately.
 *
 * Which languages exist is [LocaleRegistry]'s job; which are enabled and active is
 * `LocalePreferences`'.
 */
data class KeyboardLocale(
    /** Stable identifier (`en_US`, `es_ES`, `ne_NP`), persisted as the active/enabled language and
     * as `WordEntity.locale`. */
    val id: String,
    /** Name in English, for Settings. */
    val displayName: String,
    /** Name in the language itself ("Español", "नेपाली") — what the spacebar and the language
     * picker show, since someone looking for their language recognises it written in its own
     * script. */
    val nativeName: String,
    val letterLayout: KeyboardLayout,
    /** The language's name on the spacebar, from the pack manifest ("PT BR"); null means
     * [language] in capitals. See [spacebarName]. */
    val shortLabel: String? = null,
    /** Layouts besides [letterLayout] that belong to this language, such as its shift layer. */
    val extraLayouts: List<KeyboardLayout> = emptyList(),
    /** Letter layouts the user may pick from, [letterLayout] among them; empty when there's no
     * choice. See [withLetterLayout]. */
    val letterLayoutChoices: List<KeyboardLayout> = emptyList(),
    val languageModel: ModelSource,
    /** Text rules and per-language data tables — what counts as a word, sentence punctuation,
     * contractions, emoji words. */
    val profile: LanguageProfile,
) {
    /** ISO 639 language code, for matching a text field's `EditorInfo.hintLocales`. */
    val language: String get() = id.substringBefore('_')

    /** What the spacebar calls this language: "EN", "FR", or the pack's own label ("PT BR"). */
    val spacebarName: String get() = shortLabel ?: language.uppercase()

    /** This language with the user's chosen letter layout, if [layoutId] is one of its choices. */
    fun withLetterLayout(layoutId: String?): KeyboardLocale {
        val chosen = letterLayoutChoices.firstOrNull { it.id == layoutId } ?: return this
        return if (chosen == letterLayout) this else copy(letterLayout = chosen)
    }

    companion object {
        val EnUs = KeyboardLocale(
            id = "en_US",
            displayName = "English (US)",
            nativeName = "English",
            letterLayout = Layouts.QwertyEnUS,
            languageModel = ModelSource.Asset("lm_en_us.bin"),
            profile = LanguageProfile.English,
        )

        /** The built-in language: always available, needs no download, and what a fresh install
         * types in. */
        val Default = EnUs

        /** Languages that ship inside the APK. Downloaded ones are added through [LocaleRegistry]. */
        val bundled = listOf(EnUs)
    }
}

/** Where a language's model lives: packaged with the app, or installed from a language pack. */
sealed interface ModelSource {
    /** Under `core/src/main/assets`, stored uncompressed so it can be memory-mapped. */
    data class Asset(val name: String) : ModelSource

    /** An absolute path inside the app's private storage, written by the pack installer. */
    data class File(val path: String) : ModelSource
}
