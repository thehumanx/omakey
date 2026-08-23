package dev.omakey.core.language

import dev.omakey.core.language.nepali.NepaliTransliterator
import dev.omakey.core.layout.KeyboardLayout
import dev.omakey.core.layout.Layouts
import dev.omakey.core.layout.NepaliLayouts

enum class InputMethodKind { DIRECT, TRANSLITERATED }

/** Converts the raw Latin keystrokes typed so far for the current word into the script a
 * [InputMethodKind.TRANSLITERATED] input method actually produces (e.g. Nepali Romanized ->
 * Devanagari). Always re-run against the *whole* raw buffer, not incrementally — later keystrokes
 * can change how earlier ones are read (e.g. "s" alone is स but "sh" together is श), so a fresh
 * full re-render per keystroke is the only way to get that right. See [NepaliTransliterator] for
 * the concrete rule set. */
fun interface Transliterator {
    fun transliterate(raw: String): String
}

data class InputMethodDefinition(
    val id: String,
    val displayName: String,
    val layout: KeyboardLayout,
    val kind: InputMethodKind,
    val transliterator: Transliterator? = null,
)

data class LanguageDefinition(
    val id: String,
    val displayName: String,
    val nativeName: String,
    val inputMethods: List<InputMethodDefinition>,
    /** Bundled wordlist/bigram asset file names (see DictionarySeeder), same format/location as
     * the existing English ones — null means no corpus is bundled yet, in which case seeding is
     * skipped entirely for this language rather than erroring, and prediction/autocorrect simply
     * stays empty until one is added. */
    val dictionaryAsset: String? = null,
    val bigramAsset: String? = null,
) {
    val defaultInputMethod: InputMethodDefinition get() = inputMethods.first()
}

/** Bundled languages. English is the only one enabled by default (see [LanguagePreferences]) —
 * every other entry here ships off, opt-in via Settings > Manage Languages, so nothing changes
 * for existing users until they turn one on. */
object Languages {
    val EnglishUS = LanguageDefinition(
        id = "en_us",
        displayName = "English (US)",
        nativeName = "English (US)",
        inputMethods = listOf(
            InputMethodDefinition(
                id = "en_us_qwerty",
                displayName = "QWERTY",
                layout = Layouts.QwertyEnUS,
                kind = InputMethodKind.DIRECT,
            ),
        ),
        dictionaryAsset = "wordlist_en_us.txt",
        bigramAsset = "bigrams_en_us.txt",
    )

    val Nepali = LanguageDefinition(
        id = "ne_np",
        displayName = "Nepali",
        nativeName = "नेपाली",
        inputMethods = listOf(
            InputMethodDefinition(
                id = "ne_np_romanized",
                displayName = "Romanized",
                layout = Layouts.QwertyEnUS,
                kind = InputMethodKind.TRANSLITERATED,
                transliterator = NepaliTransliterator,
            ),
            InputMethodDefinition(
                id = "ne_np_traditional",
                displayName = "Traditional",
                layout = NepaliLayouts.Traditional,
                kind = InputMethodKind.DIRECT,
            ),
        ),
        dictionaryAsset = null,
        bigramAsset = null,
    )

    val all: List<LanguageDefinition> = listOf(EnglishUS, Nepali)

    fun byId(id: String): LanguageDefinition? = all.find { it.id == id }
}
