package dev.omakey.core.locale

import dev.omakey.core.layout.KeyboardLayout
import dev.omakey.core.layout.Layouts

/**
 * The set of things that have to change together to support a language.
 *
 * omakey is single-locale, and this class does **not** change that — there is exactly one instance.
 * What it changes is that "English (US)" stops being an assumption spread across the codebase as
 * bare references to `Layouts.QwertyEnUS` and a default asset name buried in `LanguageModel.load`,
 * and becomes a named thing with one definition.
 *
 * That matters because adding a second language was the change most likely to be asked for and the
 * most expensive to make: it touches layout, prediction, autocorrect and the emoji word-table
 * simultaneously, and nothing in the code said so. Now the list is here, and the compiler points at
 * the call sites.
 *
 * ### What a second locale would actually require
 *
 * Adding an entry here is the easy tenth of it. The rest:
 *
 *  1. **A letter layout** — [letterLayout]. Straightforward; `Layouts` already models rows and
 *     width weights. Note `KeyboardGeometry`'s coordinates mirror QWERTY and would need their own
 *     per-layout version before the spatial model could ever be switched on for it.
 *  2. **A language model asset** — [languageModelAsset], built by `scripts/build_lm.py` against a
 *     corpus for that language. This is the bulk of the work and the bulk of the APK.
 *  3. **Autocorrect's channel model**, which encodes which keys are adjacent to which — derived
 *     from the layout, so it follows from (1), but it is a real dependency rather than a free one.
 *  4. **`WordEmojiSuggestions`**, which is an English word table and would simply be wrong.
 *  5. **A way for the user to choose**, plus the runtime switch: the personal dictionary is keyed
 *     by word with no language column, so two locales sharing one `words` table would teach each
 *     other's vocabulary. That is a schema migration, and it is the part most likely to be
 *     discovered late.
 *
 * Point 5 is the reason this class exists rather than a bare `currentLayout` variable: the seam is
 * cheap now and the schema consequence is not.
 */
data class KeyboardLocale(
    /** Stable identifier, suitable for persisting once there is more than one to choose between. */
    val id: String,
    val displayName: String,
    val letterLayout: KeyboardLayout,
    /** Asset name under `core/src/main/assets`, memory-mapped by `LanguageModel.load`. */
    val languageModelAsset: String,
) {
    companion object {
        val EnUs = KeyboardLocale(
            id = "en_US",
            displayName = "English (US)",
            letterLayout = Layouts.QwertyEnUS,
            languageModelAsset = "lm_en_us.bin",
        )

        /** The only locale there is. Every site that used to name `Layouts.QwertyEnUS` directly for
         * *keyboard* purposes goes through here, so adding a second one surfaces them all. */
        val Default = EnUs

        val all = listOf(EnUs)
    }
}
